#!/usr/bin/env bash
# =============================================================================
# Arthas 启动脚本(交互模式) —— 使用本机已安装的 Arthas 4.0.4
#   路径: /Users/yc/environment/arthas-4.0.4 (不存在时给出安装指引, 不自动下载)
#
# 用法:
#   ./scripts/arthas.sh                 # 交互模式: 会列出 Java 进程让你选择
#   ./scripts/arthas.sh <pid>           # 直接 attach 指定进程
#   批处理(非交互执行, 适合脚本/留档):   # ⚠️ 4.x 用 --batch-file 而非 -c
#     printf 'thread -n 3\nexit\n' > /tmp/batch.as
#     ./scripts/arthas.sh <pid> --batch-file /tmp/batch.as
#
# 常用命令速记(详见 docs/06-工具速查表.md):
#   dashboard            总览面板 | thread -n 3  CPU 最高3线程 | thread -b  死锁源头
#   heapdump --live ...  堆转储   | trace 类 方法 '#cost>100'  慢调用追踪
# =============================================================================
set -euo pipefail

ARTHAS_HOME="${ARTHAS_HOME:-/Users/yc/environment/arthas-4.0.4}"

if [ ! -f "$ARTHAS_HOME/arthas-boot.jar" ]; then
  echo "未找到 Arthas: $ARTHAS_HOME/arthas-boot.jar"
  echo "本脚本默认使用本机已装的 4.0.4; 换机器时可设置环境变量 ARTHAS_HOME 指向你的 arthas 目录,"
  echo "或安装: curl -O https://arthas.aliyun.com/arthas-boot.jar && java -jar arthas-boot.jar"
  exit 1
fi

# 透传全部参数给 arthas-boot: 无参则进入交互选择进程模式
exec java -jar "$ARTHAS_HOME/arthas-boot.jar" "$@"
