# troubleshoot-lab · 线上故障排查演练场

本地可跑、零外部依赖的 Java/Spring Boot 故障演练项目：**17 个生产典型故障场景**，
全部可 HTTP 触发/停止/反复演练，每个场景配套完整 Runbook
（可观测性视角 → 原生工具定位 → 根因 → 修复对比 → 停止恢复验证 → Arthas 进阶）。
数据层按**企业级订单域**建模（orders 100 万行 × 29 字段宽表 + order_item 300 万行等 5 张表）。
目的不是展示故障，而是训练**线上排查思维与实践能力**。

- JDK 21 · Spring Boot 3.4.5 · MySQL 8（本地） · Arthas 4.0.4（本地安装，不下载）
- 核心契约：**停止即确定性回落**——17 个场景 stop 内置恢复动作，无需等待 GC 或重启（契约见 docs/01 §4.3）
- 设计模式：Strategy / Template Method / Registry / Builder / Facade（见 docs/01 附录）

## 快速开始

```bash
# 0) 环境自检（JDK 21+ / Maven / 本机 MySQL 8）
java -version && mvn -version && mysql -h127.0.0.1 -uroot -prootroot -e "SELECT VERSION();"

# 1) 启动（自动打包 + 教学 JVM 参数 + 健康检查；首次自动建库灌数约 2~4 分钟）
./scripts/lab.sh

# 2) 控制台触发故障，另开终端按手册排查
open http://localhost:8080
```

> ⚠️ **凭据** `root/rootroot` 仅限本机教学，可用 `MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD` 覆盖；
> 本机无 MySQL 时 `cd docker && docker compose up -d` 起备用实例（demo 默认不用 Docker）。

## 场景总表

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
| 17 | `oom-direct-memory` | 堆外内存 OOM | DirectBuffer/Netty 未释放，堆直方图看不见 | [02 手册](docs/02-内存类场景手册.md) |

> ⚠️ **演练纪律：一次只触发一个场景**——多故障叠加是定位的地狱模式；
> 动手排查前先 `./scripts/diagnose.sh` 留现场，瞬态故障不等慢条斯理的人。

## 文档

| 文档 | 内容 |
|---|---|
| [01 总纲](docs/01-排查方法论与工具优先级.md) | 文档地图与读法 · 问题全景 · 可观测性前置 · P0~P4 工具优先级 · macOS/Linux 差异 · **跨场景通用机制正主**（GC 惰性 / OOM catch 纪律 / 停止即回落契约 / jstat 口径陷阱） |
| [02 内存类](docs/02-内存类场景手册.md) | 场景 01~04、17（含容器内存模型附录） |
| [03 CPU 类](docs/03-CPU类场景手册.md) | 场景 05~07 |
| [04 线程与 IO 类](docs/04-线程与IO类场景手册.md) | 场景 08~11 |
| [05 接口慢类](docs/05-接口慢类场景手册.md) | 场景 12~16（含订单域 schema、索引设计与数据倾斜附录） |
| [06 工具速查表](docs/06-工具速查表.md) | jcmd / Arthas / MySQL 纯命令查阅 |

## 常用命令

```bash
./scripts/lab.sh                                    # 启动(后台+健康检查)
./scripts/lab.sh -status                            # 状态一览(PID/端口/JVM含committed/数据行数)
./scripts/lab.sh -doctor                            # 环境体检(JDK/Maven/MySQL/端口/Arthas/磁盘)
./scripts/lab.sh -stop                              # 优雅停止(PID身份校验防误杀)
./scripts/lab.sh -restart                           # 重启(灌数中重启也安全)
./scripts/lab.sh -clean                             # 清 MySQL 演练库(需先停止, -y 跳过确认)
./scripts/lab.sh -clean-all                         # 清库+演练产物(dumps/diagnostics/旧日志)
./scripts/lab.sh -restart -clean-all                # 工厂重置
SEED_USER_SKEW=80 ./scripts/lab.sh -restart -clean-all   # 倾斜灌数(80%订单落1%热点用户)
./scripts/lab.sh -restart -wait                     # 重启并阻塞等灌数完成(脚本化/CI)
JAVA_OPTS_EXTRA='-Xmx512m' ./scripts/lab.sh         # 临时加大堆复现不同故障强度
./scripts/diagnose.sh                               # 一键快照现场到 diagnostics/
./scripts/arthas.sh                                 # Arthas attach(自动选中演练场进程)
curl localhost:8080/api/faults                      # 场景列表
curl -X POST 'localhost:8080/api/faults/oom-heap/trigger?mode=crash'   # 例: 真OOM+dump
curl -X POST localhost:8080/api/faults/<id>/stop    # 停止任意场景
curl -X POST localhost:8080/api/faults/quiz         # 盲测模式: 随机出题只给告警(reveal 揭晓)
```

> PID 记录在 `logs/app.pid`，日志在 `logs/app.log`。

## 目录结构

```
scripts/  lab.sh(统一管理入口) diagnose.sh(现场快照) arthas.sh(Arthas 4.0.4)
docs/     01总纲 02内存 03CPU 04线程IO 05接口慢 06速查表
src/      core/设计模式骨架  web/门面与业务接口  fault/{memory,cpu,threadio,slowapi}/16场景
docker/   docker-compose.yml(备用 MySQL, 默认不用)
dumps/ diagnostics/ logs/   演练产物(已 gitignore)
```

## ⚠️ 注意事项

- **教学参数勿照抄生产**：256m 小堆 / Hikari 池=2 / Tomcat=50 均为刻意配置以加速复现，
  每处教学配置在代码与配置文件中都有注释说明真实含义；
- **运行中拒绝清库**：`-clean` / `-clean-all` 在应用运行时会被拒绝——运行中删库等于制造真事故；
- **crash 模式会真实 OOM**：`?mode=crash` 触发真 `OutOfMemoryError` 并自动落 dump；
  场景自动自愈转 ERROR（控制台原因可读），点停止即复位——但线上 OOM 的标准动作是重启；
- **Arthas 退出纪律**：会话内 `exit` 只断开客户端，彻底卸载 agent 执行 `stop`；
- macOS 演练与 Linux 生产的命令差异已在手册中以 🍎 标注，生产操作以 Linux 版为准。
