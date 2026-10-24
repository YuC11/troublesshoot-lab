#!/usr/bin/env bash
# =============================================================================
# 演练场启动脚本：打包 + 以「教学 JVM 参数」启动
#
# 为什么不用 mvn spring-boot:run？——它无法精确控制 JVM 参数，而小堆
# (256m/64m metaspace) 是快速复现 OOM/GC 故障的关键。线上同理：JVM 参数
# 必须显式落在启动命令/发布系统里，而不是依赖默认值。
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/troubleshoot-lab-1.0.0.jar

# 1. 打包（已存在且源码未变时跳过, 加 -U 不强刷）
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "==> 打包中..."
  mvn -q -DskipTests package
fi

mkdir -p dumps diagnostics logs

# 2. 教学 JVM 参数（每个参数都是 docs 里的教学点）
#    -Xms/-Xmx 256m      : 小堆, 让内存类故障分钟级复现
#    -Xss512k            : 小栈, 更容易触发深度递归类问题
#    -XX:MaxMetaspaceSize=64m : Metaspace 上限, 场景02 的靶子
#    -XX:+HeapDumpOnOutOfMemoryError : OOM 自动落 dump 到 dumps/
JAVA_OPTS="-Xms256m -Xmx256m -Xss512k \
 -XX:MaxMetaspaceSize=64m \
 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps \
 -Dfile.encoding=UTF-8"

echo "==> 启动 troubleshoot-lab (JVM: $JAVA_OPTS)"
echo "==> 控制台:   http://localhost:8080"
echo "==> 场景列表: curl http://localhost:8080/api/faults"
echo "==> 首次启动灌 100 万行数据约 15~40s, 进度看下方日志"
exec java $JAVA_OPTS -jar "$JAR" 2>&1 | tee logs/app.log
