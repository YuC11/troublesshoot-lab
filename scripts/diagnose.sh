#!/usr/bin/env bash
# =============================================================================
# 一键现场快照 —— 「先留现场, 再动手」的线上纪律
#
# 排查前先执行本脚本: 把进程的全部证据(线程栈×2/CPU线程排行/类直方图/
# GC状态/句柄/网络连接)固化到 diagnostics/<时间戳>/ 目录。
# 原理: 很多故障是「瞬态」的, 等你慢慢一条条敲命令, 现场已经变了。
# 相当于给进程拍 CT, 之后再从容分析, 重启也不怕丢证据。
#
# 工具选型: JDK 9+ 一律 jcmd 优先(演进映射见 docs/01 手册 4.2);
#           jstack/jmap 仅作 JDK 8 老服务器兜底。jstat 保留(流式趋势采样无 jcmd 等价)。
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

# 定位演练场进程: 优先 jps, 找不到再用 ps 兜底
PID=$(jps -l 2>/dev/null | grep -i troubleshoot | awk '{print $1}' | head -1 || true)
if [ -z "${PID}" ]; then
  PID=$(ps aux | grep 'troubleshoot-lab' | grep -v grep | awk '{print $2}' | head -1 || true)
fi
if [ -z "${PID}" ]; then
  echo "未找到 troubleshoot-lab 进程, 请先 ./scripts/start.sh"; exit 1
fi

OUT="diagnostics/$(date +%Y%m%d-%H%M%S)-pid${PID}"
mkdir -p "$OUT"
echo "==> 快照输出: $OUT (pid=$PID)"

# 1. 进程与线程级 CPU: top 两层抓取(进程层 + 线程层, 按CPU排序取前30)
top -l 1 -o cpu -n 15 -stats pid,command,cpu,mem,threads > "$OUT/top-process.txt" 2>&1 || true
top -l 1 -o cpu -stats pid,command,cpu,time -n 30 > "$OUT/top-threads-hint.txt" 2>&1 || true
# macOS 无 top -H, 用 ps 按线程 CPU 排序近似(足够教学定位)
# ⚠️ 平台差异: macOS 的 ps -M 要求 -o 紧跟其后; Linux 用 ps -T -p
ps -M -o tid,pcpu,time,comm -p "$PID" 2>/dev/null | sort -k2 -rn | head -40 > "$OUT/threads-by-cpu.txt" \
  || ps -T -o spid,pcpu,time,comm -p "$PID" 2>/dev/null | sort -k2 -rn | head -40 > "$OUT/threads-by-cpu.txt" \
  || true
# 跨平台兜底: Thread.print 的 cpu= 字段直接按线程 CPU 排序(jcmd 与 jstack 输出同格式)
jcmd "$PID" Thread.print 2>/dev/null | sed -E 's/^"([^"]+)".*cpu=([0-9.]+)ms.*/\2 \1/' \
  | grep -E '^[0-9]+(\.[0-9]+)? ' | sort -rn | head -20 \
  > "$OUT/threads-by-cpu-threadprint.txt" || true

# 2. 线程栈 ×2 (间隔2s): 连续采样才能看出「钉在同一处」的栈
#    JDK 9+: jcmd Thread.print 为正统; JDK 8 老服务器用 jstack 兜底
jcmd "$PID" Thread.print > "$OUT/thread-print-1.txt" 2>/dev/null \
  || jstack "$PID" > "$OUT/thread-print-1.txt" 2>&1 || true
sleep 2
jcmd "$PID" Thread.print > "$OUT/thread-print-2.txt" 2>/dev/null \
  || jstack "$PID" > "$OUT/thread-print-2.txt" 2>&1 || true

# 3. GC 状态: 堆快照(jcmd) + 趋势采样(jstat —— jcmd 无流式, 趋势仍是 jstat 正解)
jcmd "$PID" GC.heap_info > "$OUT/heap-info.txt" 2>&1 || true
jstat -gcutil "$PID" > "$OUT/jstat-gcutil.txt" 2>&1 || true
jstat -gc "$PID" > "$OUT/jstat-gc.txt" 2>&1 || true
jstat -gcmetacapacity "$PID" > "$OUT/jstat-gcmetacapacity.txt" 2>&1 || true

# 4. 堆对象直方图: 活体分析谁占大头(byte[]? HashMap? 自定义类?)
#    JDK 9+: GC.class_histogram 为正统; JDK 8 用 jmap -histo 兜底
jcmd "$PID" GC.class_histogram 2>/dev/null | head -50 > "$OUT/class-histogram-top50.txt" \
  || jmap -histo "$PID" 2>/dev/null | head -50 > "$OUT/class-histogram-top50.txt" || true

# 5. 句柄与连接: 句柄泄漏(场景11)与连接异常的证据
lsof -p "$PID" 2>/dev/null | wc -l > "$OUT/lsof-count.txt" || true
lsof -p "$PID" 2>/dev/null | awk '{print $NF}' | sort | uniq -c | sort -rn | head -30 > "$OUT/lsof-by-file-top30.txt" || true
netstat -an 2>/dev/null | grep -E 'ESTABLISHED|LISTEN' | head -50 > "$OUT/netstat.txt" || true

# 6. 进程环境快照: 启动参数(教学JVM参数是否生效)与运行时长
jcmd "$PID" VM.flags > "$OUT/vm-flags.txt" 2>&1 || true
jcmd "$PID" VM.uptime > "$OUT/vm-uptime.txt" 2>&1 || true

echo "==> 完成. 分析建议:"
echo "    CPU 高   → threads-by-cpu-threadprint.txt 找线程号, 在 thread-print-*.txt 里搜对应 nid"
echo "    内存     → heap-info.txt 看分区快照, jstat-gcutil.txt 看趋势, class-histogram-top50.txt 看对象大头"
echo "    死锁     → grep -A 10 'deadlock' thread-print-1.txt"
echo "    句柄     → lsof-count.txt 与 lsof-by-file-top30.txt"
