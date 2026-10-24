package com.lab.troubleshoot.fault.slowapi;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景 14：慢外部依赖（无超时）—— 「下游抖一下，上游全站瘫」。
 *
 * <p>【病灶原型】RestTemplate 默认<b>不设超时</b>（connect/read 均无限等待）。
 * 平日下游 50ms 响应毫无问题；某天下游 GC 频繁/容量不足变 3s，
 * 每个请求都挂着 3 个 Tomcat 线程干等——线程池迅速打满，<b>全站</b>接口排队。
 * 这是「我的代码没改过为什么会挂」类事故的标准答案。</p>
 *
 * <p>【演练联动】本场景后台 25 个线程循环调用本应用 mock 慢下游（3s），
 * 持续占用 Tomcat 线程；演练者同时 curl 对照组：
 * {@code /api/payment/with-timeout}（配了 500ms 超时，快速失败）vs
 * {@code /api/payment/no-timeout}（无超时，跟着 hang）——
 * 「一行超时配置」的价值差立现。</p>
 */
@Slf4j
@Component
public class SlowExternalCallScenario extends AbstractFaultScenario {

    @Value("${server.port:8080}")
    private int port;

    private volatile boolean running;
    private final AtomicInteger activeCallers = new AtomicInteger();

    @Override
    public String id() { return "slow-external-no-timeout"; }

    @Override
    public String name() { return "慢外部依赖：HTTP 无超时拖垮全站"; }

    @Override
    public FaultCategory category() { return FaultCategory.SLOW_API; }

    @Override
    public int order() { return 14; }

    @Override
    public String alertText() {
        return "【P0告警】troubleboot-lab 全站接口 P99 3.4s, Tomcat 线程池打满(busy 50/50); "
                + "下游支付服务(payment-svc) P99 从 45ms 恶化至 3s —— 疑似级联故障";
    }

    @Override
    public String symptom() {
        return "25 个后台线程无超时调用 3s 慢下游，占满 Tomcat 线程。curl /api/payment/no-timeout 体验 hang, "
                + "/api/payment/with-timeout 对照(快速失败)。对照 docs/14-慢外部依赖.md。";
    }

    @Override
    public String docFile() { return "14-慢外部依赖.md"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        int callers = intParam(params, "callers", 25);
        running = true;

        // 病灶 HTTP 客户端：默认 SimpleClientHttpRequestFactory 不设任何超时 = 无限等待
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        RestTemplate noTimeoutClient = new RestTemplate(factory);

        ExecutorService pool = Executors.newFixedThreadPool(callers, r -> {
            Thread t = new Thread(r, "downstream-caller");
            t.setDaemon(true);
            return t;
        });
        String slowUrl = "http://localhost:" + port + "/api/mock/downstream?delayMs=3000";

        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                activeCallers.incrementAndGet();
                try {
                    while (running) {
                        try {
                            // 每次调用在 mock 端占一个 Tomcat 工作线程睡 3s —— 25 个 caller 持续占 25 个
                            noTimeoutClient.getForObject(slowUrl, String.class);
                        } catch (Exception e) {
                            log.warn("[slow-external] 调用异常(预期内, 继续压): {}", e.getMessage());
                        }
                    }
                } finally {
                    activeCallers.decrementAndGet();
                }
            });
        }
        log.warn("[slow-external-no-timeout] {} 个无超时 caller 已启动(下游 3s)。对照体验: "
                        + "curl http://localhost:{}/api/payment/no-timeout (hang) vs /api/payment/with-timeout (500ms 失败)",
                callers, port);
    }

    @Override
    public void doStop() {
        running = false;
        log.info("[slow-external-no-timeout] caller 已停止, Tomcat 线程将逐步释放 —— 全站接口应恢复");
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "running", running,
                "activeCallers", activeCallers.get(),
                "tomcatMaxThreads", 50,
                "downstream", "http://localhost:" + port + "/api/mock/downstream?delayMs=3000"
        );
    }
}
