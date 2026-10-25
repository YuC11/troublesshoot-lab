#!/usr/bin/env bash
# =============================================================================
# 演练场统一管理脚本(唯一入口)
#
# 用法:
#   ./scripts/lab.sh                启动(后台运行+健康检查, PID 记录到 logs/app.pid)
#   ./scripts/lab.sh -stop          停止(SIGTERM 优雅关闭, 超时 SIGKILL; 未运行时友好提示)
#   ./scripts/lab.sh -restart       重启(未运行则直接启动; 与 -clean 组合=完全重置)
#   ./scripts/lab.sh -clean         清除 MySQL 演练库(要求进程已停止; 交互确认, -y 跳过)
#   组合: -stop -clean | -restart -clean ;  通用: -y 跳过确认 | -h 帮助
#
# 健壮性设计(为什么这么做):
#   * 停止前校验 PID 身份(命令行含 troubleshoot-lab) —— 防 PID 复用后误杀无关进程
#   * PID 文件陈旧(进程已死/已被复用)时自动回退 jps/ps 发现, 不盲信文件
#   * -clean 在应用运行时拒绝执行 —— 运行中删库等于制造真事故; 必须先 -stop 或用组合
#   * 启动幂等: 已运行则报告 PID 后退出; restart 天然幂等(未运行就直接启动)
#   * 启动前检查 java(需 ≥21)/端口占用; 启动后健康检查 + 灌数进度提示
#   * stop 失败(进程杀不掉)返回非零, 组合命令据此中断, 不会带病清库/重启
#   * 端口支持 PORT 环境变量覆盖(需与 application.yml 的 server.port 一致)
#   * app.log 超过 20MB 自动轮转为 .1, 防长期演练日志膨胀
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/troubleshoot-lab-1.0.0.jar
PID_FILE=logs/app.pid
LOG_FILE=logs/app.log
LOG_ROTATE_BYTES=$((20 * 1024 * 1024))
DB_NAME=troubleboot_lab
PORT="${PORT:-8080}"   # 需与 application.yml 的 server.port 一致; 可 PORT=9090 ./scripts/lab.sh 覆盖
PING_URL="http://localhost:${PORT}/api/health/ping"
# 凭据与环境变量覆盖规则与 application.yml 保持一致
MYSQL_HOST="${MYSQL_HOST:-localhost}"; MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}";      MYSQL_PASSWORD="${MYSQL_PASSWORD:-rootroot}"

DO_STOP=0; DO_RESTART=0; DO_CLEAN=0; ASSUME_YES=0

usage() {
  cat <<'EOF'
用法: lab.sh [动作...] [选项]
  (无动作)          启动服务(后台运行+健康检查)
  -stop             停止服务
  -restart          重启服务(未运行则直接启动)
  -clean            清除 MySQL 演练库(需已停止, 交互确认)
  -stop -clean      停止并清库
  -restart -clean   完全重置: 停止+清库+重新启动
选项:
  -y                跳过 -clean 的交互确认
  -h                显示本帮助
EOF
}

# ── 进程发现: PID 文件(校验身份) → jps → ps ─────────────────────────────────
find_running_pid() {
  # 1) PID 文件: 必须同时满足「进程存在」与「身份匹配」两个条件, 防 PID 被复用误杀
  if [ -f "$PID_FILE" ]; then
    local p; p=$(cat "$PID_FILE" 2>/dev/null || true)
    if [ -n "$p" ] && kill -0 "$p" 2>/dev/null \
       && ps -p "$p" -o command= 2>/dev/null | grep -q 'troubleshoot-lab'; then
      echo "$p"; return 0
    fi
  fi
  # 2) jps 发现(JVM 官方途径) → 3) ps 兜底(jps 不可用/进程非 java 启动)
  local p2; p2=$(jps -l 2>/dev/null | grep -i 'troubleshoot' | awk '{print $1}' | head -1 || true)
  if [ -z "$p2" ]; then
    p2=$(ps aux | grep '[t]roubleshoot-lab' | grep '\.jar' | awk '{print $2}' | head -1 || true)
  fi
  if [ -n "$p2" ]; then echo "$p2"; return 0; fi
  return 1
}

# ── 停止: 优雅关闭 → 超时强杀 → 失败返回非零 ────────────────────────────────
do_stop() {
  local pid; pid=$(find_running_pid 2>/dev/null || true)
  if [ -z "$pid" ]; then
    echo "==> 进程未在运行, -stop 无需执行(已是目标状态)"
    rm -f "$PID_FILE"; return 0
  fi
  echo "==> 停止 troubleshoot-lab (pid=$pid, SIGTERM 优雅关闭...)"
  kill "$pid" 2>/dev/null || true
  local i; for i in $(seq 1 20); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "==> 20s 未退出, SIGKILL 强制终止"; kill -9 "$pid" 2>/dev/null || true; sleep 1
  fi
  if kill -0 "$pid" 2>/dev/null; then
    echo "!! 停止失败(进程仍存活), 请手动检查: ps -p $pid"; return 1
  fi
  rm -f "$PID_FILE"
  echo "==> 已停止"
}

# ── 清库: 运行中拒绝 → 确认 → 删除 → 验证 ──────────────────────────────────
do_clean() {
  local pid; pid=$(find_running_pid 2>/dev/null || true)
  if [ -n "$pid" ]; then
    echo "!! 拒绝清库: 应用正在运行(pid=$pid), 运行中删库会制造真事故"
    echo "   先停止: ./scripts/lab.sh -stop    或组合: ./scripts/lab.sh -restart -clean"
    return 1
  fi
  if ! command -v mysql >/dev/null 2>&1; then
    echo "!! 未找到 mysql 客户端, 无法自动清库(可手动执行: DROP DATABASE ${DB_NAME};)"; return 1
  fi
  echo "目标: 删除 MySQL ${MYSQL_HOST}:${MYSQL_PORT} 上的数据库 [${DB_NAME}] (账号 ${MYSQL_USER})"
  if [ "$ASSUME_YES" != 1 ]; then
    local c; read -r -p "确认删除? 该操作不可恢复 (y/N): " c || c=""
    case "$c" in y|Y) ;; *) echo "已取消, 未做任何修改"; return 0 ;; esac
  fi
  export MYSQL_PWD="$MYSQL_PASSWORD"   # 用环境变量传密码, 避免命令行明文告警
  if ! mysql --connect-timeout=5 -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" \
       -e "DROP DATABASE IF EXISTS ${DB_NAME};" 2>/dev/null; then
    echo "!! 连接/删除失败: 检查 MySQL 是否启动、凭据是否正确(可用 MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD 覆盖)"; return 1
  fi
  local left; left=$(mysql --connect-timeout=5 -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" \
      -N -e "SHOW DATABASES LIKE '${DB_NAME}';" 2>/dev/null | wc -l | tr -d ' ')
  if [ "$left" != "0" ]; then echo "!! 验证失败: 库仍存在"; return 1; fi
  echo "==> ${DB_NAME} 已删除。下次启动会自动重建并重灌数据。"
}

# ── 启动: 幂等 → 前置检查 → 构建 → 后台拉起 → 健康检查 ─────────────────────
do_start() {
  local pid; pid=$(find_running_pid 2>/dev/null || true)
  if [ -n "$pid" ]; then
    echo "==> 已在运行 (pid=$pid): http://localhost:${PORT}  —— 如需重启请用 -restart"; return 0
  fi

  # 前置检查: java 版本(编译目标是 21, 低版本 JDK 无法加载 61.0 字节码)
  local java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  if ! command -v "$java_bin" >/dev/null 2>&1; then
    echo "!! 未找到 java —— 请安装 JDK 21+ 或设置 JAVA_HOME"; return 1
  fi
  local major; major=$("$java_bin" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
  if ! [ "${major:-0}" -ge 21 ] 2>/dev/null; then
    echo "!! 需要 JDK 21+(当前主版本: ${major:-未知}) —— 设置 JAVA_HOME 指向 JDK 21 后重试"; return 1
  fi

  # 前置检查: 端口被非本应用进程占用(占用者不明时启动必然失败, 提前暴露)
  if command -v lsof >/dev/null 2>&1 \
     && lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | grep -q LISTEN; then
    echo "!! 端口 ${PORT} 已被其他进程占用: lsof -nP -iTCP:${PORT} -sTCP:LISTEN 查看占用者"; return 1
  fi

  # 构建(源码比 jar 新时重建; 需要 mvn)
  if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -print -quit 2>/dev/null)" ]; then
    if ! command -v mvn >/dev/null 2>&1; then
      echo "!! 需要构建但未找到 mvn"; return 1
    fi
    echo "==> 打包中..."
    mvn -q -DskipTests package
  fi

  mkdir -p logs dumps diagnostics

  # 日志轮转: 上一次运行留下过大日志时归档, 防长期演练膨胀
  if [ -f "$LOG_FILE" ] && [ "$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)" -gt "$LOG_ROTATE_BYTES" ]; then
    mv "$LOG_FILE" "${LOG_FILE}.1"
    echo "==> 旧日志已轮转: ${LOG_FILE}.1"
  fi

  # 教学 JVM 参数(每个参数都是 docs 里的教学点):
  #   小堆 256m 快速复现内存故障 / 512k 栈 / Metaspace 上限 64m(场景02) /
  #   OOM 自动落 dump 到 dumps/
  local java_opts="-Xms256m -Xmx256m -Xss512k -XX:MaxMetaspaceSize=64m \
 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps -Dfile.encoding=UTF-8"

  nohup "$java_bin" $java_opts -jar "$JAR" > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  local new_pid; new_pid=$(cat "$PID_FILE")
  echo "==> 已后台启动 (pid=$new_pid), 健康检查中(最长 120s)..."

  local i; local ready=0
  for i in $(seq 1 120); do
    if curl -sf -m 2 "$PING_URL" >/dev/null 2>&1; then ready=1; break; fi
    if ! kill -0 "$new_pid" 2>/dev/null; then
      echo "!! 进程启动后异常退出, 日志尾部:"; tail -15 "$LOG_FILE"; return 1
    fi
    sleep 1
  done
  if [ "$ready" != 1 ]; then
    echo "!! 120s 未就绪(可能卡在构建/灌数), 最近日志:"; tail -15 "$LOG_FILE"
    echo "   进程保留(pid=$new_pid)以便排查, 详情: tail -f $LOG_FILE"; return 1
  fi
  echo "==> 就绪: http://localhost:${PORT} (pid=$new_pid)"
  if grep -q "开始灌入" "$LOG_FILE" 2>/dev/null && ! grep -q "初始化完成" "$LOG_FILE" 2>/dev/null; then
    echo "==> 首次灌数进行中(约 2~4 分钟, 期间 DB 接口暂不可用), 跟踪: tail -f $LOG_FILE"
  fi
}

# ── 参数解析: 未知参数只报两行, 详情指向 -h ─────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    -stop) DO_STOP=1 ;;
    -restart) DO_RESTART=1 ;;
    -clean) DO_CLEAN=1 ;;
    -y|--yes) ASSUME_YES=1 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "lab.sh: 未知参数 '$1'" >&2
      echo "用法: lab.sh [-stop] [-restart] [-clean] [-y] [-h]  —— 详情: ./scripts/lab.sh -h" >&2
      exit 1 ;;
  esac
  shift
done

mkdir -p logs dumps diagnostics

# 执行语义: stop/restart 都先停; clean 紧随其后; restart 最后再启动
# (-restart -clean = 完全重置: 停止→清库→启动)。
# 前面任一步失败(非零)时 set -e 中断, 不会带病执行后续步骤。
if [ "$DO_STOP" = 1 ] || [ "$DO_RESTART" = 1 ]; then
  do_stop
fi
if [ "$DO_CLEAN" = 1 ]; then
  do_clean
fi
if [ "$DO_RESTART" = 1 ]; then
  echo "==> [-restart] 重新启动..."
  do_start
elif [ "$DO_STOP" = 0 ] && [ "$DO_CLEAN" = 0 ]; then
  do_start
fi
