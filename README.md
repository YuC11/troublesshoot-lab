# troubleshoot-lab · 线上故障排查演练场

一个**本地可跑、零外部依赖**（连你本机的 MySQL）的 Java/Spring Boot 故障演练项目：
**16 个生产典型故障场景**，每个都可 HTTP 触发/停止/反复演练，配套
「可观测性视角 → 原生工具定位 → 根因 → 修复对比 → Arthas 进阶」的完整 Runbook。
数据层按**企业级订单域**建模（orders 100 万行 × 29 字段宽表 + order_item 300 万行等 5 张表，
索引设计解读见 [docs/05 附录](docs/05-接口慢类场景手册.md)）。
目的不是展示故障，而是训练**线上排查思维与实践能力**。

- JDK 21 · Spring Boot 3.4.5 · MySQL 8（本地） · Arthas 4.0.4（本地 `/Users/yc/environment/arthas-4.0.4`）
- 设计模式：Strategy + Template Method + Registry + Builder + Facade（详见 [docs/01 第五节](docs/01-排查方法论与工具优先级.md)）

## 快速开始（3 步）

```bash
# 0) 环境自检（需 JDK21+ / Maven / 本机 MySQL 8）
java -version && mvn -version && mysql -h127.0.0.1 -uroot -prootroot -e "SELECT VERSION();"

# 1) 启动（统一入口 lab.sh: 自动打包+教学JVM参数+后台运行+健康检查;
#    首次自动建库灌数: orders 100万×29列 + order_item 300万, 约 2~4 分钟）
./scripts/lab.sh

# 2) 打开控制台触发故障, 另开终端按手册排查
open http://localhost:8080
```

数据库缺省凭据 `root/rootroot`，可用环境变量覆盖：
`MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD`。
本机没有 MySQL？`cd docker && docker compose up -d` 起一个备用的（demo 本身不依赖 docker）。

## 16 个场景总表

| # | 场景 ID | 名称 | 一句话病灶 | 手册 |
|---|---|---|---|---|
| 01 | `oom-heap` | 堆内存 OOM | static 集合当无界缓存 | [02 手册](docs/02-内存类场景手册.md) |
| 02 | `oom-metaspace` | Metaspace OOM | 动态类/类加载器泄漏 | 同上 |
| 03 | `leak-threadlocal` | ThreadLocal 泄漏 | 线程池 set 不 remove | 同上 |
| 04 | `gc-storm` | GC 风暴 ⭐误诊王 | 内存不足伪装 CPU 高 | 同上 |
| 05 | `cpu-busy-loop` | 业务死循环 | 循环条件永真 | [03 手册](docs/03-CPU类场景手册.md) |
| 06 | `cpu-regex-backtrack` | 正则回溯 ReDoS | 嵌套量词指数回溯 | 同上 |
| 07 | `cpu-json-storm` | 序列化风暴 | 大对象反复 JSON 化 | 同上 |
| 08 | `thread-deadlock` | 死锁 | 反序获取双锁 | [04 手册](docs/04-线程与IO类场景手册.md) |
| 09 | `thread-pool-exhausted` | 线程池耗尽 | 垃圾任务拖死共享池 | 同上 |
| 10 | `thread-leak` | 线程泄漏 | new Thread 不复用 | 同上 |
| 11 | `io-handle-leak` | 句柄泄漏 | 流不 close | 同上 |
| 12 | `slow-sql-index-failure` | 慢 SQL·索引列序错误 ⭐闭环 | 有索引用不上（最左前缀） | [05 手册](docs/05-接口慢类场景手册.md) |
| 13 | `slow-n-plus-1` | N+1 查询 | 慢查询日志抓不到的慢 | 同上 |
| 14 | `slow-external-no-timeout` | 慢外部依赖 | HTTP 无超时级联拖垮 | 同上 |
| 15 | `slow-lock-contention` | 锁竞争 | 锁内做慢 IO | 同上 |
| 16 | `slow-db-pool-exhausted` | 连接池耗尽 ⭐综合 | 慢SQL+池小的连锁反应 | 同上 |

> 必读顺序：[01 排查方法论](docs/01-排查方法论与工具优先级.md)（含可观测性前置认知、
> P0~P4 工具优先级、macOS/Linux 命令差异）→ 按兴趣进各手册 → [06 工具速查表](docs/06-工具速查表.md)。

## 带新人/自我训练的建议玩法

1. **闯关模式**：控制台只看「告警现象」卡片，不看手册，独立排查 30 分钟；
2. 排查时先问三个问题：**ping 快不快？GC 正常吗？线程都在等什么？**
3. 卡住再看手册的「定位路线图」，最后才展开「分步命令」对照；
4. 每个场景务必体验 **stop 恢复对比**（修复验证是排查闭环的一半）；
5. 场景 05/08/12 再用 **Arthas 复盘一遍**（`thread -n 3` / `thread -b` / `trace`），
   体会「一条命令等价手工三步」；
6. 纪律：**一次只触发一个场景**；动手前 `./scripts/diagnose.sh` 留现场。

## 项目结构

```
scripts/  lab.sh(统一管理: 启动/-stop/-clean) diagnose.sh(一键现场快照) arthas.sh(本地4.0.4)
docs/     01方法论 02内存 03CPU 04线程IO 05接口慢 06速查表（6 份整合手册）
src/      core/设计模式骨架  web/门面与业务接口  fault/{memory,cpu,threadio,slowapi}/16场景
docker/   docker-compose.yml（备用 MySQL，demo 默认不用）
dumps/ diagnostics/ logs/   演练产物（已 gitignore）
```

## 常用命令备忘

```bash
./scripts/lab.sh                                    # 统一管理入口: 启动(后台+健康检查)
./scripts/lab.sh -status                            # 状态一览(PID/端口/JVM/数据行数)
./scripts/lab.sh -stop                              # 优雅停止(未运行时友好提示, PID身份校验防误杀)
./scripts/lab.sh -restart                           # 重启(未运行则直接启动; 灌数中重启也安全)
./scripts/lab.sh -clean                             # 清除MySQL演练库(需先停止, 交互确认, -y 跳过)
./scripts/lab.sh -clean-all                         # 清库+演练产物(dumps/diagnostics/旧日志)
./scripts/lab.sh -stop -clean                       # 组合: 停止并清库
./scripts/lab.sh -restart -clean-all                # 工厂重置: 停止+清库清产物+重启
./scripts/lab.sh -restart -wait                     # 重启并阻塞等待数据灌数完成(脚本化/CI)
JAVA_OPTS_EXTRA='-Xmx512m' ./scripts/lab.sh         # 临时加大堆复现不同故障强度
./scripts/diagnose.sh                               # 一键快照现场到 diagnostics/
./scripts/arthas.sh                                 # Arthas attach（交互）
curl localhost:8080/api/faults                      # 场景列表
curl -X POST localhost:8080/api/faults/oom-heap/trigger?mode=crash   # 例: 真OOM+dump
curl -X POST localhost:8080/api/faults/<id>/stop    # 停止任意场景
```

> PID 记录在 `logs/app.pid`，日志在 `logs/app.log`。

## 已知说明

- macOS 本机演练与 Linux 生产的命令差异已在手册中以 🍎 标注（`top -Hp`、nid 进制、
  `timeout`、`ss` 等），生产操作以 Linux 版为准；
- 演练场进程为教学参数（256m 小堆/池=2/Tomcat=50），**不要照抄到生产**；
  每处教学配置在代码/配置文件中都有注释说明真实含义。
