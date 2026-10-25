#!/usr/bin/env bash
# =============================================================================
# 演练场统一管理脚本(唯一入口)
#
# 用法:
#   ./scripts/lab.sh                启动(后台运行+健康检查, PID 记录到 logs/app.pid)
#   ./scripts/lab.sh -stop          停止(SIGTERM 优雅关闭, 超时 SIGKILL; 未运行时友好提示)
#   ./scripts/lab.sh -restart       重启(未运行则直接启动; 与 -clean 组合=完全重置)
#   ./scripts/lab.sh -status        状态一览(PID/端口/JVM/数据行数)
#   ./scripts/lab.sh -clean         清除 MySQL 演练库(要求进程已停止; 交互确认, -y 跳过)
#   ./scripts/lab.sh -clean-all     清库+演练产物(dumps/diagnostics/旧日志)
#   组合: -stop -clean | -restart -clean | -restart -clean-all(工厂重置)
#   选项: -wait 启动后等待数据灌数完成 | -y 跳过确认 | -h 帮助
#   环境变量:
#     PORT=9090                      覆盖端口(需与 application.yml 一致)
#     JAVA_OPTS_EXTRA='-Xmx512m'     追加/覆盖 JVM 参数(如临时加大堆复现不同故障强度)
#     MYSQL_HOST/PORT/USER/PASSWORD  覆盖数据库凭据
#
# 健壮性设计(为什么这么做):
#   * 停止前校验 PID 身份(命令行含 troubleshoot-lab) —— 防 PID 复用后误杀无关进程
#   * PID 文件陈旧(进程已死/已被复用)时自动回退 jps/ps 发现, 不盲信文件
#   * -clean/-clean-all 在应用运行时拒绝执行 —— 运行中删库等于制造真事故
#   * 启动幂等: 已运行则报告 PID 后退出; restart 天然幂等(未运行就直接启动)
#   * 启动用 mkdir 锁防并发双开(陈旧锁 5 分钟自动失效); 任意退出路径自动清锁
#   * 启动前检查 java(需 ≥21)/端口占用; 启动后健康检查 + 灌数进度提示
#   * stop 失败(进程杀不掉)返回非零, 组合命令据此中断, 不会带病清库/重启
#   * app.log 超过 20MB 自动轮转为 .1, 防长期演练日志膨胀
#
# 已知局限(教学单机场景可接受):
#   * kill -0 无法探测其他用户的进程 —— 多用户机器上 stop 可能误报"未运行"
#   * macOS 无 setsid —— 终端会话异常关闭可能波及后台进程; Linux 生产无此问题
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/troubleshoot-lab-1.0.0.jar
PID_FILE=logs/app.pid
LOG_FILE=logs/app.log
LOG_ROTATE_BYTES=$((20 * 1024 * 1024))
LOCK_DIR=logs/app-start.lock
DB_NAME=troubleboot_lab
PORT="${PORT:-8080}"   # 需与 application.yml 的 server.port 一致
PING_URL="http://localhost:${PORT}/api/health/ping"
# 凭据与环境变量覆盖规则与 application.yml 保持一致
MYSQL_HOST="${MYSQL_HOST:-localhost}"; MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}";      MYSQL_PASSWORD="${MYSQL_PASSWORD:-rootroot}"

DO_STOP=0; DO_RESTART=0; DO_CLEAN=0; DO_CLEAN_ALL=0; DO_STATUS=0; DO_DOCTOR=0; DO_WAIT=0; ASSUME_YES=0
ARTHAS_HOME="${ARTHAS_HOME:-/Users/yc/environment/arthas-4.0.4}"

usage() {
  sed -n '3,17p' "$0" | sed 's/^# \{0,1\}//'
}

# 任意退出路径释放启动锁(防脚本被 Ctrl-C/异常中断后留下陈旧锁)
trap 'rm -rf "$LOCK_DIR" 2>/dev/null || true' EXIT

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

# ── 状态: PID/端口/JVM/数据 一屏 ────────────────────────────────────────────
do_status() {
  local pid; pid=$(find_running_pid 2>/dev/null || true)
  if [ -z "$pid" ]; then
    echo "状态: 未运行 (可用 ./scripts/lab.sh 启动)"
    return 0
  fi
  echo "状态: 运行中 (pid=$pid)"
  local ping_ms; ping_ms=$(curl -sf -o /dev/null -w '%{time_total}' -m 3 "$PING_URL" 2>/dev/null || true)
  if [ -n "$ping_ms" ]; then
    echo "    端口 ${PORT}: 就绪 (${ping_ms}s)"
  else
    echo "    端口 ${PORT}: 未响应(可能仍在启动/灌数)"
  fi
  local metrics; metrics=$(curl -sf -m 3 "http://localhost:${PORT}/api/metrics" 2>/dev/null || true)
  if [ -n "$metrics" ] && command -v python3 >/dev/null 2>&1; then
    echo "$metrics" | python3 -c "
import json, sys
d = json.load(sys.stdin)
up = d['uptimeSeconds']
print(f\"    JVM: uptime {up//60}m{up%60}s | 堆 {d['heapUsedMb']}/{d['heapMaxMb']}MB \"
      f\"| Metaspace {d.get('metaspaceUsedMb','?')}MB(已用)/{d.get('metaspaceCommittedMb','?')}MB(已提交,撞墙口径) \"
      f\"| 线程 {d['threadCount']} | 已加载类 {d['loadedClassCount']:,}\")" \
      || echo "    JVM 指标: 解析失败(原始: $metrics)"
  fi
  if command -v mysql >/dev/null 2>&1; then
    export MYSQL_PWD="$MYSQL_PASSWORD"
    local rows; rows=$(mysql --connect-timeout=3 -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -N \
        -e "SELECT CONCAT('orders=', COUNT(*)) FROM ${DB_NAME}.orders;
            SELECT CONCAT('order_item=', COUNT(*)) FROM ${DB_NAME}.order_item;" 2>/dev/null \
        | tr '\n' ' ' | tr -s ' ')
    if [ -n "$rows" ]; then
      local o; o=$(echo "$rows" | grep -o 'orders=[0-9]*' | cut -d= -f2 || echo 0)
      local note=""; [ "${o:-0}" -gt 0 ] && [ "${o:-0}" -lt 1000000 ] 2>/dev/null && note=" (灌数进行中...)"
      echo "    数据: ${rows}${note}"
    else
      echo "    数据: 不可用(库未创建或凭据不符)"
    fi
  fi
}

# ── 清理: 运行中拒绝 → 确认 → 删库(+产物) → 验证 ────────────────────────────
do_clean() {
  local pid; pid=$(find_running_pid 2>/dev/null || true)
  if [ -n "$pid" ]; then
    echo "!! 拒绝清理: 应用正在运行(pid=$pid), 运行中删库等于制造真事故"
    echo "   先停止: ./scripts/lab.sh -stop    或组合: ./scripts/lab.sh -restart -clean-all"
    return 1
  fi
  local scope="MySQL 演练库 [${DB_NAME}]"
  [ "$DO_CLEAN_ALL" = 1 ] && scope="${scope} + 演练产物(dumps/ diagnostics/ 旧日志)"
  echo "目标: ${MYSQL_HOST}:${MYSQL_PORT} 上的 ${scope} (账号 ${MYSQL_USER})"
  if [ "$ASSUME_YES" != 1 ]; then
    local c; read -r -p "确认删除? 该操作不可恢复 (y/N): " c || c=""
    case "$c" in y|Y) ;; *) echo "已取消, 未做任何修改"; return 0 ;; esac
  fi
  # ① MySQL 演练库
  if command -v mysql >/dev/null 2>&1; then
    export MYSQL_PWD="$MYSQL_PASSWORD"   # 用环境变量传密码, 避免命令行明文告警
    if ! mysql --connect-timeout=5 -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" \
         -e "DROP DATABASE IF EXISTS ${DB_NAME};" 2>/dev/null; then
      echo "!! 连接/删除失败: 检查 MySQL 是否启动、凭据是否正确(MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD 覆盖)"; return 1
    fi
    local left; left=$(mysql --connect-timeout=5 -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" \
        -N -e "SHOW DATABASES LIKE '${DB_NAME}';" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$left" != "0" ]; then echo "!! 验证失败: 库仍存在"; return 1; fi
    echo "==> 库 ${DB_NAME} 已删除"
  else
    echo "!! 未找到 mysql 客户端, 跳过删库(可手动执行: DROP DATABASE ${DB_NAME};)"
  fi
  # ② 演练产物(-clean-all): 堆 dump(单个40MB+)/现场快照/轮转日志, 均为可重建的演练废弃物
  if [ "$DO_CLEAN_ALL" = 1 ]; then
    rm -rf dumps diagnostics
    rm -f logs/app.log.1
    echo "==> 演练产物已清除(dumps/ diagnostics/ 旧日志)"
  fi
  echo "==> 清理完成。下次启动会自动重建库并重灌数据。"
}

# ── 启动: 并发锁 → 幂等 → 前置检查 → 构建 → 后台拉起 → 健康检查 ─────────────
do_start() {
  # 并发锁: 两个终端同时 start 会竞态双开; mkdir 原子性天然当锁,
  # 陈旧锁(>5min, 如上次脚本被 kill -9)自动失效; 脚本任意退出由 EXIT trap 清锁
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    if [ -z "$(find "$LOCK_DIR" -maxdepth 0 -mmin +5 2>/dev/null)" ]; then
      echo "!! 另一个启动/重启正在进行(锁: $LOCK_DIR); 若确认无启动在跑可删除该目录后重试"; return 1
    fi
    echo "==> 发现陈旧启动锁(>5min), 自动清除"
    rm -rf "$LOCK_DIR"
    mkdir "$LOCK_DIR"
  fi

  local pid; pid=$(find_running_pid 2>/dev/null || true)
  if [ -n "$pid" ]; then
    echo "==> 已在运行 (pid=$pid): http://localhost:${PORT}  —— 如需重启请用 -restart"
    rmdir "$LOCK_DIR" 2>/dev/null || true
    return 0
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
  #   小堆 256m 快速复现内存故障 / 512k 栈 / Metaspace 上限 96m(场景02:
  #   撞墙口径是 committed≈6KB/加载器≈6.7×used, 温和4000个需~25MB committed 余量, 64m 放不下) /
  #   DirectMemory 上限 64m+NMT(场景17) / GC 全量事件流 logs/gc.log(jstat 是采样, GC日志是每次停顿的第一手记录;
  #   -Xlog 语法: 段1=选择器gc, 段2=file输出, 段3=time/uptime装饰, 段4=轮转) /
  #   OOM 自动落 dump 到 dumps/ ; JAVA_OPTS_EXTRA 可追加覆盖(如 -Xmx512m)
  local java_opts="-Xms256m -Xmx256m -Xss512k -XX:MaxMetaspaceSize=96m \
 -XX:MaxDirectMemorySize=64m -XX:NativeMemoryTracking=summary \
 -Xlog:gc:file=logs/gc.log:time,uptime:filecount=3,filesize=20M \
 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps -Dfile.encoding=UTF-8 \
 ${JAVA_OPTS_EXTRA:-}"

  nohup "$java_bin" $java_opts -jar "$JAR" > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  rmdir "$LOCK_DIR" 2>/dev/null || true   # PID 文件已落盘, 锁的使命结束
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
  echo "==> 服务就绪: http://localhost:${PORT} (pid=$new_pid)"

  # 数据就绪: 默认只提示; -wait 则阻塞到灌数完成(脚本化/CI 场景需要确定性)
  if grep -q "初始化完成" "$LOG_FILE" 2>/dev/null; then
    echo "==> 数据已就绪"
  elif grep -q "开始灌入" "$LOG_FILE" 2>/dev/null; then
    echo "==> 首次灌数进行中(约 2~4 分钟), 跟踪: tail -f $LOG_FILE"
    if [ "$DO_WAIT" = 1 ]; then
      echo "==> [-wait] 等待数据就绪(最长 10 分钟)..."
      local w; local ok=0
      for w in $(seq 1 120); do
        if grep -q "初始化完成" "$LOG_FILE" 2>/dev/null; then ok=1; break; fi
        if ! kill -0 "$new_pid" 2>/dev/null; then
          echo "!! 等待期间进程退出, 日志尾部:"; tail -10 "$LOG_FILE"; return 1
        fi
        sleep 5
      done
      if [ "$ok" != 1 ]; then
        echo "!! 10 分钟未完成灌数, 详情: tail -f $LOG_FILE"; return 1
      fi
      echo "==> 数据就绪"
    fi
  fi
}

# ── 环境体检: JDK/Maven/MySQL/端口/Arthas/磁盘 逐项 ✅❌ (新机器上手第一件事) ──
do_doctor() {
  local fail=0
  local chk; chk() { # chk <名称> <结果0/1> <明细>
    if [ "$2" = 0 ]; then echo "  ✅ $1: $3"; else echo "  ❌ $1: $3"; fail=1; fi
  }
  echo "==> 环境体检 (-doctor)"
  # JDK
  if command -v java >/dev/null 2>&1; then
    local jv; jv=$(java -version 2>&1 | head -1)
    local major; major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')
    [ "${major:-0}" -ge 21 ] 2>/dev/null; chk "JDK ≥21" $? "$jv"
  else
    chk "JDK ≥21" 1 "未安装 java"
  fi
  command -v mvn >/dev/null 2>&1; chk "Maven" $? "$(mvn -version 2>/dev/null | head -1 || echo 未安装)"
  # MySQL 连通
  if command -v mysql >/dev/null 2>&1; then
    MYSQL_PWD="$MYSQL_PASSWORD" mysql --connect-timeout=3 -h "$MYSQL_HOST" -P "$MYSQL_PORT" \
      -u "$MYSQL_USER" -N -e "SELECT 1" >/dev/null 2>&1
    chk "MySQL 连通($MYSQL_HOST:$MYSQL_PORT)" $? "凭据 ${MYSQL_USER}/******"
  else
    chk "MySQL" 1 "未安装 mysql 客户端"
  fi
  # 端口
  if lsof -i ":$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    local lpid; lpid=$(lsof -t -i ":$PORT" -sTCP:LISTEN 2>/dev/null | head -1)
    if [ "$lpid" = "$(find_running_pid 2>/dev/null || true)" ]; then
      echo "  ✅ 端口 $PORT: 本演练场占用 (pid=$lpid)"
    else
      chk "端口 $PORT" 1 "被其他进程占用 (pid=$lpid)"
    fi
  else
    echo "  ✅ 端口 $PORT: 空闲"
  fi
  # Arthas
  [ -f "$ARTHAS_HOME/arthas-boot.jar" ]; chk "Arthas 本地安装" $? "$ARTHAS_HOME"
  # 磁盘
  local free_gb; free_gb=$(df -g . | awk 'NR==2{print $4}')
  [ "${free_gb:-0}" -ge 2 ] 2>/dev/null; chk "磁盘余量≥2G" $? "当前目录剩余 ${free_gb:-?}G"
  if [ "$fail" = 0 ]; then
    echo "==> 全部通过, 可以开始演练"
  else
    echo "==> 存在阻塞项, 先解决上方 ❌" >&2
    return 1
  fi
}

# ── 参数解析: 未知参数只报两行, 详情指向 -h ─────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    -stop) DO_STOP=1 ;;
    -restart) DO_RESTART=1 ;;
    -clean) DO_CLEAN=1 ;;
    -clean-all) DO_CLEAN=1; DO_CLEAN_ALL=1 ;;
    -status) DO_STATUS=1 ;;
    -doctor) DO_DOCTOR=1 ;;
    -wait) DO_WAIT=1 ;;
    -y|--yes) ASSUME_YES=1 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "lab.sh: 未知参数 '$1'" >&2
      echo "用法: lab.sh [-stop] [-restart] [-status] [-doctor] [-clean] [-clean-all] [-wait] [-y] [-h]" >&2
      exit 1 ;;
  esac
  shift
done

mkdir -p logs dumps diagnostics

# 状态/体检是独立动作(不与启停组合): 输出一屏后结束
if [ "$DO_STATUS" = 1 ]; then
  do_status
  exit 0
fi
if [ "$DO_DOCTOR" = 1 ]; then
  do_doctor
  exit $?
fi

# 执行语义: stop/restart 都先停; clean(-all) 紧随其后; restart 最后再启动
# (-restart -clean-all = 工厂重置: 停止→清库清产物→启动)。
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
