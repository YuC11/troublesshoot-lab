package com.lab.troubleshoot.fault.cpu;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 场景 06：正则回溯风暴（ReDoS）—— CPU 高的「框架层」变种。
 *
 * <p>【病灶原型】嵌套量词 {@code (a+)+$} 遇到「几乎匹配成功」的输入（大量 a + 结尾一个 b），
 * 回溯次数随输入长度<b>指数级</b>增长。常见于校验用户输入的正则写法不当，
 * 恶意或异常输入直接把线程钉死在 {@code java.util.regex} 里。</p>
 *
 * <p>【教学价值】定位路径与场景 05 相同（top -Hp → nid → jstack），但栈落在
 * JDK 内部（Pattern$Curly.match0 / Pattern$GroupHead.match）——训练「栈在框架里
 * 怎么反推业务代码」：往上翻栈帧，找到提交匹配的那行业务代码。
 * 区分「业务死循环 / 框架开销 / GC 空转」三种 CPU 高，是本场景组的核心教学目标。</p>
 *
 * <p>注意 JDK9+ 把正则匹配从递归改成了部分循环实现，深回溯抛的可能是
 * {@code StackOverflowError}——线上真实表现，栈同样指向 Pattern，照样定位得到。</p>
 */
@Slf4j
@Component
public class RegexBacktrackScenario extends AbstractFaultScenario {

    /** 教科书级灾难正则：外层量词包裹内层量词 → 回溯组合爆炸 */
    private static final Pattern EVIL = Pattern.compile("(a+)+$");

    private volatile boolean running;
    private final AtomicLong attempts = new AtomicLong();

    @Override
    public String id() { return "cpu-regex-backtrack"; }

    @Override
    public String name() { return "正则回溯风暴（ReDoS）"; }

    @Override
    public FaultCategory category() { return FaultCategory.CPU; }

    @Override
    public int order() { return 6; }

    @Override
    public String alertText() {
        return "【P0告警】troubleboot-lab CPU 198%(2核打满), 仅 /api/user/register 接口超时; "
                + "线程 dump 显示 2 个工作线程持续 RUNNING, 无 GC 异常 —— 疑似计算型阻塞";
    }

    @Override
    public String symptom() {
        return "2 个线程持续用灾难正则 (a+)+$ 匹配长输入，单次匹配即耗时数秒（指数回溯）。"
                + "jstack 栈深陷 java.util.regex —— 学会从框架栈反推业务行。对照 docs/03-CPU类场景手册.md#场景06。";
    }

    @Override
    public String docFile() { return "03-CPU类场景手册.md#场景06"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        int length = intParam(params, "length", 26); // 回溯量级 ~2^26，单次匹配秒级
        int workers = intParam(params, "workers", 2);
        running = true;

        // 「几乎能匹配成功」的输入才是回溯炸弹：结尾一个 b 让每条路径都试完才失败
        String input = "a".repeat(length) + "b";

        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "regex-burner");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                while (running) {
                    try {
                        Matcher matcher = EVIL.matcher(input);
                        boolean matched = matcher.matches(); // ← 灾难发生点：jstack 栈顶就在这一带
                        attempts.incrementAndGet();
                        if (matched) {
                            log.warn("[cpu-regex] 不可能命中的匹配竟然命中了?!");
                        }
                    } catch (StackOverflowError soe) {
                        // JDK9+ 深回溯可能以 SOE 呈现——线上真实表现，记录后继续
                        attempts.incrementAndGet();
                    }
                }
            });
        }
        log.warn("[cpu-regex-backtrack] 回溯风暴已启动 (input length={})。定位: top -Hp {} → jstack 看 Pattern$ 栈帧",
                input.length(), pid());
    }

    @Override
    public void doStop() {
        running = false;
        // 当前这一次匹配可能还要跑几秒才结束——这正是 ReDoS「停不下来」的体验
        log.info("[cpu-regex-backtrack] 开关已置 false（当次匹配需数秒自然结束）—— CPU 将在几秒内回落");
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "backtracking", running,
                "matchAttempts", attempts.get()
        );
    }
}
