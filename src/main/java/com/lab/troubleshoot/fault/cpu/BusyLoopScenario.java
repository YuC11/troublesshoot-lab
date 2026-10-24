package com.lab.troubleshoot.fault.cpu;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景 05：业务死循环 —— 最经典的「CPU 高」定位教学。
 *
 * <p>【病灶原型】循环退出条件写错（边界条件、并发标志、状态机漏迁移），
 * 某个分支永远退不出去。QPS 不变但 CPU 打满，单核/几核 100%。</p>
 *
 * <p>【教学定位路径】top -Hp &lt;pid&gt; 找到高 CPU 线程 → 线程号转 16 进制 →
 * jstack | grep nid=0x&lt;hex&gt; → 栈帧直达本类 {@link #burnCpu()} 的行号。
 * 本场景的 run() / burnCpu() 两个栈帧特意起得「一眼可读」，让第一次演练的人
 * 也能在 30 秒内体验到「从告警到代码行」的完整链路。</p>
 *
 * <p>防 JIT 死码消除：计算结果周期性写入 volatile 字段——否则 JIT 可能优化掉
 * 整个循环，故障复现不出来（这也是编写性能故障注入代码的常见坑）。</p>
 */
@Slf4j
@Component
public class BusyLoopScenario extends AbstractFaultScenario {

    private volatile boolean running;

    /** 死码消除的出口：计算结果必须「被使用」，JVM 才不会删掉整个循环 */
    private volatile double sink;

    private final AtomicLong loopCount = new AtomicLong();

    @Override
    public String id() { return "cpu-busy-loop"; }

    @Override
    public String name() { return "业务死循环：CPU 打满"; }

    @Override
    public FaultCategory category() { return FaultCategory.CPU; }

    @Override
    public int order() { return 5; }

    @Override
    public String alertText() {
        return "【P0告警】troubleboot-lab CPU 使用率 389%（4核打满）, 但 QPS 与平时一致(约 320/s); "
                + "无 Full GC 增长, 无慢 SQL —— 单实例 CPU 异常";
    }

    @Override
    public String symptom() {
        return "4 个线程空转烧 CPU。top -Hp 找高 CPU 线程 → 16 进制 → jstack 定位到 burnCpu 代码行。"
                + "对照 docs/03-CPU类场景手册.md#场景05（附 Arthas 热修复死循环全流程）。";
    }

    @Override
    public String docFile() { return "03-CPU类场景手册.md#场景05"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        int burners = intParam(params, "burners", 4);
        running = true;

        ExecutorService pool = Executors.newFixedThreadPool(burners, r -> {
            Thread t = new Thread(r, "cpu-burner");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < burners; i++) {
            pool.submit(() -> {
                // 死循环主体：while 条件依赖 volatile 开关——「退出条件只差一个 volatile」
                // 正是很多线上死循环事故的真相（改一行代码的事，但要先定位得到）
                while (running) {
                    loopCount.incrementAndGet();
                    burnCpu(100_000);
                }
            });
        }
        log.warn("[cpu-busy-loop] {} 个死循环线程已启动。定位: top -Hp {} → 16进制线程号 → jstack {} | grep -A 20 nid",
                burners, pid(), pid());
    }

    /**
     * 有名有姓的计算函数：让 jstack 栈帧直接出现 burnCpu + 行号，
     * 演练者第一次就能完成「告警 → 代码行」的完整闭环。
     */
    private void burnCpu(int iterations) {
        double acc = 0;
        for (int i = 1; i <= iterations; i++) {
            acc += Math.sqrt(i) * Math.sin(i);
        }
        // 写 volatile「使用」结果：防 JIT 死码消除（性能故障注入的标准技巧）
        sink = acc;
    }

    @Override
    public void doStop() {
        running = false;
        log.info("[cpu-busy-loop] 开关已置 false, 死循环线程退出 —— CPU 应回落（对照 top 观察 5s）");
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "burning", running,
                "loopCount", loopCount.get(),
                "sink", sink
        );
    }
}
