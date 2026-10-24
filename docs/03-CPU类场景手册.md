# 03 · CPU 类场景手册（场景 05~07）

> 三个场景共用同一条定位主线（top → nid → jstack），差别在**栈落在哪**：
> 业务自己的方法（05）/ JDK 正则框架（06）/ 序列化框架（07）。
> 加上场景 04（栈查无此线程 = GC），四种「CPU 高」就此集齐。
> 🍎 标注 = macOS 差异，Linux 命令为主。

---

## 场景 05 · 业务死循环：CPU 打满（定位教学的主场景）

**触发**：`POST /api/faults/cpu-busy-loop/trigger`（4 个 burner 线程）

### 【模拟告警】
> 【P0告警】CPU 389%（4 核打满），QPS 与平时一致（约 320/s）；无 FGC 增长、无慢 SQL

### 【第0现场 · 可观测性视角】
- 指标：单实例 CPU 高，**QPS 不变** → 流量无关；GC 正常 → 内存无关；
  「负载不变而 CPU 高」直接指向计算型问题（死循环/回溯/框架开销）。
- 链路：若死循环发生在请求线程 → 该接口 RT 飙高；若在后台线程 → 接口正常
  （本场景是后台线程型：全站正常但 CPU 满 —— 只能靠线程 dump 抓）。

### 【定位路线图】（Linux 标准链路，务必形成肌肉记忆）
```
top -Hp <pid> 找最耗CPU的 TID → printf '%x' TID 转十六进制 →
jstack <pid> | grep -A 20 'nid=0x<hex>' → 读栈: 类.方法(文件:行号) → 看代码
```

### 【分步排查】

**Step 1 · 线程级 CPU 排名**
Linux: `top -Hp <pid>`，按 P 排序取前几。
🍎 macOS 本机演练：`jstack <pid> | sed -E 's/^"([^"]+)".*cpu=([0-9.]+)ms.*/\2 \1/' | sort -rn | head -6`
<details><summary>macOS 实测输出</summary>

```
54162.74 cpu-burner     ← 4 个同名 burner 合计把 CPU 吃满
54155.90 cpu-burner
54148.14 cpu-burner
54140.45 cpu-burner
 4752.73 DestroyJavaVM
```
jstack 的线程头自带 `cpu=累计毫秒`。**差分**两次（隔 3s）取增量就是实时 CPU，
效果等价 Linux 的 top -Hp。（汇总写法见 06 速查表。）
</details>

**Step 2 · 转进制并抓栈（Linux）**
```bash
TID=8423
HEX=$(printf '%x' $TID)
jstack <pid> | grep -A 20 "nid=0x$HEX"
```
🍎 macOS：nid 是线程名后**方括号里的十进制数**，如 `"cpu-burner" #57 [29703]`，
直接 `jstack <pid> | grep -A 20 '\[29703\]'` 即可，无需转进制。

<details><summary>实测栈帧（两平台一致）</summary>

```
"cpu-burner" #57 [29703] daemon ... cpu=54262.35ms ... nid=29703 runnable
   java.lang.Thread.State: RUNNABLE
	at com.lab.troubleshoot.fault.cpu.BusyLoopScenario.burnCpu(BusyLoopScenario.java:95)
	at com.lab.troubleshoot.fault.cpu.BusyLoopScenario.lambda$doTrigger$1(BusyLoopScenario.java:81)
	...
```
栈顶直指 `burnCpu` 第 95 行 —— 从告警到代码行，两分钟内闭环。
</details>

**Step 3 · 钉死证据**：隔 2~3s 再抓两次 jstack，栈**仍在同一行** = 死循环坐实
（一次采样可能是巧合，三次采样是判决）。

### 【根因】
循环退出条件失效（本场景演示的是缺 `volatile` 的开关 + 无退出的计算循环）。

### 【修复对比】
```java
// ❌ 开关非 volatile: JIT 缓存读到的永远是旧值, 循环退不出去
private boolean running = true;
while (running) { ... }

// ✅ 跨线程可见性交给 volatile
private volatile boolean running = true;
```
边界条件类死循环同理：先看循环变量的**所有**写路径，再怀疑条件永真。

### 【预防】
- code review 对「无 sleep/IO 的纯计算循环」保持警觉；
- 正式环境设置「单接口 RT 熔断」与「CPU 告警带线程 top 快照」（自动执行 diagnose.sh 思路）。

### ⭐ Arthas 一键定位（生产首选）
```
thread -n 3
```
<details><summary>实测输出</summary>

```
"cpu-burner" Id=372 deltaTime=210ms time=4610ms RUNNABLE
    at com.lab.troubleshoot.fault.cpu.BusyLoopScenario.burnCpu(BusyLoopScenario.java:95)
"cpu-burner" Id=373 deltaTime=205ms time=4603ms RUNNABLE
    at com.lab.troubleshoot.fault.cpu.BusyLoopScenario.burnCpu(BusyLoopScenario.java:95)
```
一条命令 = top -Hp + 转进制 + jstack 三步，且直接给出**行号**。

**热修复全流程**（线上不能立刻发版时的止血）：
```
jad --source-only com.lab.troubleshoot.fault.cpu.BusyLoopScenario > /tmp/B.java
# 编辑 /tmp/B.java 修掉 bug
mc /tmp/B.java --d /tmp/out
retransform /tmp/out/com/lab/troubleshoot/fault/cpu/BusyLoopScenario.class
```
验证 CPU 回落后再走正式发版。⚠️ retransform 是临时止血，重启即失效，必须补版。

---

## 场景 06 · 正则回溯风暴（ReDoS）

**触发**：`POST /api/faults/cpu-regex-backtrack/trigger`（默认 2 线程，输入 26 字符）

### 【模拟告警】
> 【P0告警】CPU 198%，仅 /api/user/register 超时；线程 dump 显示 2 个工作线程持续 RUNNING

### 【第0现场 · 可观测性视角】
- 指标：CPU 高但集中在少数线程；**只有含该校验的接口**超时（其他接口正常）；
- 链路：注册接口 Span 停在参数校验层（未进业务库/下游）；
- 日志：请求开始处理但迟迟不结束 → 计算型阻塞特征。

### 【定位路线图】
与场景 05 完全相同。差别在栈的内容——
<details><summary>实测栈帧</summary>

```
"regex-burner" #271 [102419] ... cpu=3056.85ms ... RUNNABLE
   java.lang.Thread.State: RUNNABLE
	at java.util.regex.Pattern$BmpCharPropertyGreedy.match(Pattern.java:4502)
	at java.util.regex.Pattern$GroupHead.match(Pattern.java:4969)
	at java.util.regex.Pattern$Loop.match(Pattern.java:5078)
	at java.util.regex.Pattern$GroupTail.match(Pattern.java:5000)
	...
```
整条栈都在 `java.util.regex`（JDK 内部）——**这不是「框架的锅」的证据，
而是「框架在替你受累」的证据**：顺着栈往下翻，找到提交匹配的业务帧
（本场景是 `RegexBacktrackScenario` 的 matches 调用行）。
</details>

### 【根因】
嵌套量词 `(a+)+$`：外层每个分组起点都要枚举内层所有切分，复杂度 O(2ⁿ)。
26 个字符即百万级回溯，30 字符就是秒级单次匹配。

### 【修复对比】
```java
// ❌ 灾难回溯: 嵌套量词 + 通配结尾
Pattern p = Pattern.compile("(a+)+$");

// ✅ 消除嵌套量词（占有量词 *+ / 独立原子化）
Pattern p = Pattern.compile("(a+)*+b");        // 或改写为非回溯结构
// 更好的做法: 这类「N 个 a 结尾 b」的校验根本不该用正则
boolean ok = input.matches("a+b");              // 线性复杂度
```

### 【预防】
- 用户输入的正则必须评审：**同一个字符被多个量词作用**就是危险信号；
- 校验类逻辑限制输入长度上限；
- 单元测试包含超长「几乎匹配」输入（`a`×100 + `b`）。

### ⭐ Arthas
```
thread -n 3          # 栈顶直接看到 Pattern$.match 链
watch com.lab.troubleshoot.fault.cpu.RegexBacktrackScenario * '{params,throwExp}' -x 1  # 看进参
```

---

## 场景 07 · 序列化风暴：框架开销型 CPU 高

**触发**：`POST /api/faults/cpu-json-storm/trigger`（3 线程 × ~4MB 对象图序列化+反序列化）

### 【模拟告警】
> 【P1告警】CPU 持续 150%~210%，/api/report/export RT 800ms → 4.5s，无错误无 GC 异常，出口带宽 +3 倍

### 【第0现场 · 可观测性视角】
- 指标：CPU 高 + **网络出口流量同步放大**（大 JSON 出去的物证）；
- 链路：慢接口 Span 时间均匀分布在响应序列化段（或导出接口独占 CPU）；
- 与 05/06 的区分点：RT 变长但**不挂死**、不超时，温水煮青蛙型。

### 【定位路线图】
top/jstack 同场景 05，看栈落在哪一层。
<details><summary>实测栈帧</summary>

```
"json-burner" #273 [121627] ... cpu=1763.62ms ... RUNNABLE
   java.lang.Thread.State: RUNNABLE
	at com.fasterxml.jackson.core.json.WriterBasedJsonGenerator.writeFieldName(WriterBasedJsonGenerator.java:151)
	at com.fasterxml.jackson.databind.ser.std.StdKeySerializers$StringKeySerializer.serialize(StdKeySerializers.java:268)
	...
```
栈在 Jackson —— 业务帧在栈中下部。**框架栈 ≠ 可以甩锅**：
往下翻找到是谁把 4MB 对象图交给序列化器的（本场景是报表导出全量返回）。
</details>

### 【根因】
业务选择了「全量对象图 + 反复序列化」的方案：单次 4MB JSON ≈ 每秒几十 MB 的
序列化吞吐需求，3 个线程即可打满 CPU。

### 【修复对比】
```java
// ❌ 全量查询 + 整体序列化返回
List<Order> all = orderMapper.selectAll();          // 4 万行
return R.ok(all);                                    // 4MB JSON

// ✅ 分页 + 按需字段 + 流式导出
return R.ok(orderMapper.page(pageNo, 50, fields));  // 列表页只要展示字段
// 导出走流式: StreamingResponseBody / CSV 流, 不在内存拼大 JSON
```

### 【预防】
- 大列表接口强制分页与字段裁剪（DTO 只含展示列）；
- 压测把「序列化 CPU」算进容量模型；出口带宽监控做旁证。

### ⭐ Arthas
```
thread -n 3                      # 栈顶 Jackson 帧
trace com.lab.troubleshoot.web.OrderController '*' '#cost > 100'   # 拖出慢在哪个方法
watch com.fasterxml.jackson.databind.ObjectMapper writeValueAsString '{params[0].toString().length()}' -x 1 -n 3   # 看正在序列化多大对象
```
