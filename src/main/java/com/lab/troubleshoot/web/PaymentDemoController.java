package com.lab.troubleshoot.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 支付业务接口 —— 场景 14 的「对照组教具」：同一业务、两种超时配置。
 *
 * <p>两个端点唯一区别是 RestTemplate 的超时配置。「故障代码 vs 修复代码」
 * 通常要翻 git 历史；这里并排放在一个类里，diff 一眼可见。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/payment")
public class PaymentDemoController {

    @Value("${server.port:8080}")
    private int port;

    /** 病灶版客户端：不设任何超时（RestTemplate 默认 = 无限等待） */
    private final RestTemplate noTimeoutClient = new RestTemplate(new SimpleClientHttpRequestFactory());

    /** 修复版客户端：connect 500ms + read 500ms，下游再抖也快速失败 */
    private final RestTemplate withTimeoutClient = buildTimeoutClient(500, 500);

    @GetMapping("/no-timeout")
    public Map<String, Object> payNoTimeout() {
        long start = System.currentTimeMillis();
        String resp = noTimeoutClient.getForObject(
                "http://localhost:" + port + "/api/mock/downstream?delayMs=3000", String.class);
        // 场景14未触发时本接口也要 3s —— 病灶常态就慢；触发后还会因线程池满更慢
        return Map.of("mode", "no-timeout", "costMs", System.currentTimeMillis() - start, "downstream", resp);
    }

    @GetMapping("/with-timeout")
    public Map<String, Object> payWithTimeout() {
        long start = System.currentTimeMillis();
        try {
            String resp = withTimeoutClient.getForObject(
                    "http://localhost:" + port + "/api/mock/downstream?delayMs=3000", String.class);
            return Map.of("mode", "with-timeout", "costMs", System.currentTimeMillis() - start, "downstream", resp);
        } catch (ResourceAccessException e) {
            // 500ms 读超时 → 快速失败。熔断/降级逻辑挂在这个分支上
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "下游 500ms 超时, 快速失败(总计 " + (System.currentTimeMillis() - start) + "ms): " + e.getMessage());
        }
    }

    /** 超时三件套的标准写法：connectTimeout 管建连，readTimeout 管响应 */
    private static RestTemplate buildTimeoutClient(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return new RestTemplate(f);
    }
}
