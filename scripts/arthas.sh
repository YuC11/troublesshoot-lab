#!/usr/bin/env bash
# =============================================================================
# Arthas 启动脚本 —— 使用本机已安装的 Arthas 4.0.4
#   路径: /Users/yc/environment/arthas-4.0.4 (不存在时给出安装指引, 不自动下载)
#
# 用法:
#   ./scripts/arthas.sh                 # 自动 attach 演练场进程(不再弹进程列表, 防误选)
#   ./scripts/arthas.sh <pid>           # 显式 attach 指定进程(此时不作端口清理)
#   ./scripts/arthas.sh <任意 arthas-boot 参数>   # 原样透传
#   批处理(非交互执行, 适合脚本/留档):   # ⚠️ 4.x 用 --batch-file 而非 -c
#     printf 'thread -n 3\nexit\n' > /tmp/batch.as
#     ./scripts/arthas.sh <pid> --batch-file /tmp/batch.as
#
# 退出纪律: 会话内 exit 只断开客户端, agent 仍驻留目标进程;
#   彻底卸载请执行 stop(会重置所有增强过的类)。
#
# 常用命令速记(详见 docs/06-工具速查表.md):
#   dashboard            总览面板 | thread -n 3  CPU 最高3线程 | thread -b  死锁源头
#   heapdump --live ...  堆转储   | trace 类 方法 '#cost>100'  慢调用追踪
# =============================================================================
set -euo pipefail

ARTHAS_HOME="${ARTHAS_HOME:-/Users/yc/environment/arthas-4.0.4}"
TELNET_PORT="${TELNET_PORT:-3658}"
LAB_NAME="troubleshoot-lab"

if [ ! -f "$ARTHAS_HOME/arthas-boot.jar" ]; then
  echo "未找到 Arthas: $ARTHAS_HOME/arthas-boot.jar"
  echo "本脚本默认使用本机已装的 4.0.4; 换机器时可设置环境变量 ARTHAS_HOME 指向你的 arthas 目录,"
  echo "或安装: curl -O https://arthas.aliyun.com/arthas-boot.jar && java -jar arthas-boot.jar"
  exit 1
fi

# 定位演练场进程: PID 文件(校验身份) → jps 兜底
find_lab_pid() {
  local pid_file pid
  pid_file="$(cd "$(dirname "$0")/.." && pwd)/logs/app.pid"
  if [ -f "$pid_file" ]; then
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null \
       && ps -p "$pid" -o command= 2>/dev/null | grep -q "$LAB_NAME"; then
      echo "$pid"; return 0
    fi
  fi
  jps -l 2>/dev/null | grep "$LAB_NAME" | awk '{print $1}' | head -1
  return 0
}

if [ $# -eq 0 ]; then
  lab_pid="$(find_lab_pid)"
  if [ -z "$lab_pid" ]; then
    echo "arthas.sh: 演练场进程未运行 —— 请先 ./scripts/lab.sh 启动" >&2
    echo "或显式指定目标: ./scripts/arthas.sh <pid>" >&2
    exit 1
  fi
  # 3658 被非演练场进程占用 = 上次的 agent 残留(曾误挂 IDE 等进程, 导致本次无法 attach):
  # 自动 stop 释放端口, 再 attach 正确目标
  stray="$(lsof -t -i ":$TELNET_PORT" -sTCP:LISTEN 2>/dev/null || true)"
  if [ -n "$stray" ] && [ "$stray" != "$lab_pid" ]; then
    echo "==> 端口 $TELNET_PORT 被进程 $stray(非演练场)上的残留 Arthas 占用, 自动 stop 释放..."
    java -jar "$ARTHAS_HOME/arthas-client.jar" 127.0.0.1 "$TELNET_PORT" -c "stop" >/dev/null 2>&1 || true
    sleep 1
  fi
  echo "==> 自动 attach 演练场进程 pid=$lab_pid (exit 仅断开客户端, 彻底卸载 agent 用 stop)"
  exec java -jar "$ARTHAS_HOME/arthas-boot.jar" --select "$LAB_NAME"
fi

# 有参数: 原样透传(显式 pid / --batch-file / --select 等均交由 arthas-boot 处理)
exec java -jar "$ARTHAS_HOME/arthas-boot.jar" "$@"
