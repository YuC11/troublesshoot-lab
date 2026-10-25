# _PENDING · 新增功能文档暂存（整合后本文件删除）

> 本文件是本轮「项目提升批次」的文档暂存区。所有新功能的手册内容先写在这里，
> **全部任务完成且测试通过后**再拆分并入 01/02/05/06/README 正式手册，并删除本文件。
> 范围：场景17 堆外内存 / 容器内存模型附录 / JFR 实战 / 数据倾斜灌数 / 盲测模式 /
> GC 日志 / -doctor / 复盘报告 / sparkline。

---

## A. 场景 17 · 堆外内存 OOM：Direct Memory 泄漏（→ 并入 docs/02）

**触发**：`POST /api/faults/oom-direct-memory/trigger`（温和 48 块 × 1MB，`-XX:MaxDirectMemorySize=64m`）
**crash 模式**：`?mode=crash` → 真 `OutOfMemoryError: Direct buffer memory`（自动自愈）

### 【模拟告警】
> 【P1告警】troubleboot-lab 进程 RSS 3.2GB 但堆只用 800MB；NIO 直接内存 61MB/64MB，
> BufferPool direct_count 单调上涨

### 【第0现场 · 可观测性视角】
- 指标：`jvm_buffer_memory_used_bytes{pool="direct"}` 单调上涨；
  进程 RSS 与堆使用**严重背离**（堆平稳、RSS 爬坡）——这是堆外泄漏的指纹；
- 链路/日志无异常（分配动作不报错）——靠 BufferPool 指标或 OOM 才发现。

### 【定位路线图】
```
RSS 高但堆平稳(堆外嫌疑) → jcmd GC.heap_info 排除堆/Metaspace →
jcmd VM.native_memory summary 看 Internal/Other(NMT 需 -XX:NativeMemoryTracking=summary) →
jcmd GC.class_histogram 排除堆内大头(堆外对象不占堆!) → 查代码: 谁在 allocateDirect 不释放
```

### 【分步排查】
**Step 1 · 确认「堆外嫌疑」**

```bash
jcmd <pid> GC.heap_info
```
预期：堆 used 不高——排除堆与 Metaspace 后，RSS 与堆的缺口指向堆外。

**Step 2 · 直接内存池水位**

```bash
jcmd <pid> VM.native_memory summary | grep -A 3 Internal
```
NMT 视角（lab.sh 已开 `-XX:NativeMemoryTracking=summary`）；控制台
`/api/faults/oom-direct-memory/status` 的 `directUsedMb/directMaxMb` 是同源简化版
（BufferPoolMXBean）。温和 48 块实测：direct ≈ 2.5MB → 50MB → 2.5MB。

**Step 3 · 堆直方图反证**

```bash
jcmd <pid> GC.class_histogram | head -5
```
预期：**没有异常大头**——DirectByteBuffer 的对象壳在堆里只有几十字节，
真身（native 内存）堆直方图看不见。这是与场景 01 的分水岭，也是
「堆 OOM 排查法在此失效」的教学点。

**Step 4 · 停止恢复验证**

```bash
curl -X POST http://localhost:8080/api/faults/oom-direct-memory/stop
```
stop 内置「清引用 + System.gc()」：**堆外内存的释放条件 = DirectByteBuffer 不可达 +
一次真实的 GC 执行 Cleaner**（与类卸载同款纪律，见 01 §4.1/§4.3）——
引用清空后不 GC，BufferPool 水位纹丝不动。crash 模式 OOM 后自动自愈转 ERROR。

### 【根因】
`ByteBuffer.allocateDirect(...)` 分配的堆外内存不经 GC 管辖（只受
`-XX:MaxDirectMemorySize` 限制），持有引用不释放 → 打满后新分配抛
`OutOfMemoryError: Direct buffer memory`。高频成因：Netty ByteBuf 未 release、
DirectBuffer 池无界、`FileChannel.map` 的 MappedByteBuffer 缓存。

### 【修复对比】
```java
// ❌ 分配后不释放(依赖 GC, 而 GC 恰恰不确定何时来)
ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 1024);
cache.add(buf);   // 无界持有

// ✅ ① 有界池化(Netty PooledByteBufAllocator 自带) ② 用完 release
// ③ 兜底: 容量用 BufferPoolMXBean 监控 + MaxDirectMemorySize 显式设限
```

### 【预防】
- `-XX:MaxDirectMemorySize` 显式设置（默认≈Xmx，容易吃光容器限额）；NMT 常态开启排查期；
- BufferPool direct 指标上监控（used/count 双维）；Netty 项目泄漏检测 `IoLeakAwareByteBuf`。

### ⭐ Arthas
```
memory                          # direct buffers 一屏可见
vmtool --action getInstances --className java.nio.DirectByteBuffer --limit 5
```

### 全景表行（docs/01 一、替换原「手册附思路」行）
| 内存 | 堆外 OOM `Direct buffer memory` | DirectByteBuffer/Netty ByteBuf 未释放 | 场景17 |

---

## B. 容器内存模型附录（→ 并入 docs/02 附录 B）

### 为什么「本地好好的，进容器就被 OOMKilled」

JVM 进程内存 = 堆 + Metaspace + 线程栈(线程数×Xss) + GC 开销 + 堆外(Direct/Netty) +
JIT 代码缓存。**容器 OOMKilled 看的是 cgroup limit（整个进程），不是 -Xmx**：

```
容器限额 1Gi
├─ -Xmx512m（堆, 只占一半!)
├─ Metaspace ~100m
├─ 线程栈 200线程 × 1m = 200m
├─ Direct/Netty 堆外 ~150m   ← 默认≈Xmx, 是隐藏大头
└─ JIT/GC/符号表 ~80m        → 合计 1.05Gi > 1Gi → 内核 OOMKiller 干掉进程(137)
```

要点：① `java -XX:+PrintFlagsFinal -version | grep MaxHeapSize` 验证容器内实际堆；
② JDK 10+ 默认 `UseContainerSupport`，`-XX:MaxRAMPercentage=70` 按限额比例切堆，
比写死 -Xmx 更稳；③ 留足非堆余量（经验：堆 ≤ 限额 50~70%）；
④ OOMKilled(exit 137) 排查顺序：`kubectl describe pod` 看 OOMKilled → 反推各内存区之和。

---

## C. JFR 实战（→ 并入 docs/05 场景 14/15 各加一步）

**Step N · JFR 蹲守偶发慢调用（间歇性故障的正确姿势）**

```bash
jcmd <pid> JFR.start name=slow settings=profile duration=300s filename=slow.jfr
# 让流量跑一会儿(偶发慢请求被完整记录)…
jcmd <pid> JFR.dump name=slow        # 不用等 duration 结束也可提前取
```

JMC（或 `jfr print --events jdk.JavaMonitorWait slow.jfr`）里看什么：
场景 14 → `jdk.SocketRead` 事件按耗时排序，锁下游 3s 的 read 一目了然；
场景 15 → `jdk.JavaMonitorEnter` 事件的等待时长与持锁者。
**与 Thread.print 的分工**：dump 是「此刻快照」，JFR 是「过去 N 分钟的事件流」——
蹲不到现场的问题用后者。

---

## D. 数据倾斜灌数（→ 并入 docs/05 附录 C + README 命令行）

```bash
SEED_USER_SKEW=80 ./scripts/lab.sh -restart -clean-all
```

80% 的订单落到 1% 的热点用户（user_id 1~500）——教学点：
① 索引区分度：`idx_oi_user`/user_id 查询在倾斜下返回行数暴涨；
② 优化器统计信息：灌数后 `ANALYZE TABLE orders;` 更新直方图，EXPLAIN 的 rows 估计才真实；
③ 倾斜是「测试环境正常、生产变慢」的第二大根因（数据量是第一大）。
实现：DatabaseInitializer 读 `SEED_USER_SKEW`（0~95，默认 0=均匀）。

---

## E. 盲测模式（→ 并入 docs/06 教具表 + README）

```bash
curl -X POST localhost:8080/api/faults/quiz        # 随机触发一个 READY 场景, 只返回告警+分类(不透露 id/名称)
curl -X GET  localhost:8080/api/faults/quiz/reveal # 揭晓答案(返回完整状态)
```

控制台头部「盲测」按钮：进入后场景网格隐藏，只显示告警横幅——按告警独立排查，
点「揭示」对照。规则与「一次只触发一个场景」兼容（quiz 占用触发状态机）。

---

## F. GC 日志 + NMT（→ 并入 docs/06 速查表 + lab.sh 注释）

lab.sh 教学参数新增：
- `-Xlog:gc:file=logs/gc.log:time,uptime:filecount=3,filesize=20M`——GC 全量事件流
  （jstat 是采样，GC 日志是「每一次停顿的第一手记录」；查历史问题先翻它）；
- `-XX:MaxDirectMemorySize=64m`（场景17）+ `-XX:NativeMemoryTracking=summary`（NMT，
  `jcmd VM.native_memory` 的开关，开销 ~5%）。

观察：`tail -f logs/gc.log`；每一次 Pause 行 = 一次真实停顿（时间/成因/各代前后）。

---

## G. lab.sh -doctor 与复盘报告（→ 并入 README + docs/06）

- `./scripts/lab.sh -doctor`：环境体检一条命令——JDK≥21/Maven/MySQL 连通/8080 端口/
  Arthas 本地安装/磁盘余量，✅❌ 逐项输出；
- `./scripts/diagnose.sh` 现在会在快照目录同场生成 `REPORT.md` 复盘模板：
  自动预填（时间线=快照文件清单、场景状态表）+ 待填章节（影响面/时间线叙述/根因/
  修复动作/复盘待办）——「事故报告怎么写」与「怎么查」同等重要。
