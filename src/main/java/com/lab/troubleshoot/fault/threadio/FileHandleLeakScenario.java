package com.lab.troubleshoot.fault.threadio;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景 11：文件句柄泄漏 —— Too many open files。
 *
 * <p>【病灶原型】IO 流用完不 close（尤其异常路径漏关），文件描述符（fd）持续泄漏。
 * Linux/macOS 对单进程 fd 有上限（ulimit -n，常见 1024/2560），打满后
 * <b>一切 IO 全部报错</b>：打不开文件、建不了 socket、甚至新连接进不来——
 * 「数据库连不上、端口起不来、日志写不出」的万恶之源。</p>
 *
 * <p>【教学闭环】lsof -p &lt;pid&gt; | wc -l 实时看句柄数爬升 → 看到同一个文件
 * 被打开几千次（lsof 输出肉眼可见的重复行）→ 打满后捕获真实的
 * {@code FileNotFoundException: Too many open files}。</p>
 *
 * <p>【Lombok 教学点】本场景<b>故意不用</b> {@code @Cleanup} 或 try-with-resources ——
 * 它们正是防句柄泄漏的正解；先见过事故，才懂为什么所有代码规范都强制
 * 「流必须 try-with-resources」。</p>
 */
@Slf4j
@Component
public class FileHandleLeakScenario extends AbstractFaultScenario {

    /** 泄漏用的探针文件：内容无所谓，fd 才是主角 */
    private static final File PROBE = new File("target/handle-leak-probe.bin");

    /** 持有所有已打开未关闭的流：既是泄漏现场，也是 stop 时清理的抓手 */
    private final List<InputStream> openedStreams = new CopyOnWriteArrayList<>();

    private volatile boolean leaking;
    private volatile String overflowMessage;

    static {
        try (FileOutputStream out = new FileOutputStream(PROBE)) {
            out.write("probe".getBytes()); // 1KB 都不到，够 open 即可
        } catch (IOException e) {
            throw new IllegalStateException("探针文件创建失败", e);
        }
    }

    @Override
    public String id() { return "io-handle-leak"; }

    @Override
    public String name() { return "文件句柄泄漏：Too many open files"; }

    @Override
    public FaultCategory category() { return FaultCategory.THREAD_IO; }

    @Override
    public int order() { return 11; }

    @Override
    public String alertText() {
        return "【P0告警】troubleboot-lab 突发大量 IOException: Too many open files; MySQL 连接报错 "
                + "\"Can't open file\"、新 HTTP 连接被拒 —— 进程 fd 已打满";
    }

    @Override
    public String symptom() {
        return "每秒泄漏 40 个 FileInputStream 不关闭。lsof -p <pid> | wc -l 观察句柄爬升，"
                + "打满后捕获真实 Too many open files 异常。对照 docs/04-线程与IO类场景手册.md#场景11。";
    }

    @Override
    public String docFile() { return "04-线程与IO类场景手册.md#场景11"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        int ratePerSecond = intParam(params, "rate", 40);
        int maxHandles = intParam(params, "maxHandles", 2000);
        leaking = true;
        overflowMessage = null;

        Thread leaker = new Thread(() -> {
            long intervalMs = Math.max(1000 / ratePerSecond, 1);
            try {
                while (leaking && openedStreams.size() < maxHandles) {
                    try {
                        // 故意不 close、不用 try-with-resources、不用 @Cleanup —— 这就是病灶本身
                        FileInputStream in = new FileInputStream(PROBE);
                        openedStreams.add(in);
                    } catch (IOException e) {
                        // fd 打满的第一个受害者就是它自己 —— 线上同款异常现场
                        overflowMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
                        markDamaged("句柄已打满(" + openedStreams.size() + "个): " + overflowMessage
                                + " —— 此刻本进程任何 IO(含 MySQL/socket)都会报同款错误");
                        log.error("[io-handle-leak] 句柄打满! 泄漏 {} 个后触发: {}", openedStreams.size(), overflowMessage);
                        break;
                    }
                    Thread.sleep(intervalMs);
                }
                if (leaking && overflowMessage == null) {
                    log.warn("[io-handle-leak] 已持有 {} 个未关闭句柄(上限 {})。lsof -p {} | wc -l 对照, "
                            + "加大 maxHandles 参数可复现打满", openedStreams.size(), maxHandles, pid());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "handle-leak-worker");
        leaker.setDaemon(true);
        leaker.start();
    }

    @Override
    public void doStop() {
        leaking = false;
        // 修复动作 = 把所有漏关的流补 close —— 修复后 fd 立即回落，lsof 可验证
        int n = openedStreams.size();
        for (InputStream in : openedStreams) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 清理路径上逐个兜底：单个失败不阻断其余句柄释放
            }
        }
        openedStreams.clear();
        log.info("[io-handle-leak] 已补 close {} 个流 —— lsof -p {} | wc -l 应立即回落", n, pid());
    }

    @Override
    public Map<String, Object> buildStatus() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("heldHandles", openedStreams.size());
        if (overflowMessage != null) {
            m.put("overflowError", overflowMessage);
        }
        m.put("verifyCommand", "lsof -p " + pid() + " | grep handle-leak-probe | wc -l");
        return m;
    }
}
