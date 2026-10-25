package com.lab.troubleshoot.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模拟「慢下游服务」—— 场景 14 的受害源。
 *
 * <p>真实生产里，拖垮你的往往不是自己的代码，而是一个变慢的下游。这个端点
 * 刻意做得「什么正经事都不干，纯粹睡 delayMs 毫秒」，把变量收敛到唯一：
 * 下游响应耗时。演练者可以随时调整 delayMs 复现不同程度的下游抖动。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/mock")
public class MockController {

    /** 慢下游：睡 delayMs 后返回（模拟下游 GC/慢查询/容量不足导致的响应变慢）；
     *  delayMs 封顶 30s——无上限参数会占住 Tomcat 线程数分钟, 与「无超时」病灶本身混淆 */
    @GetMapping("/downstream")
    public Map<String, Object> downstream(@RequestParam(defaultValue = "3000") long delayMs,
                                          @RequestParam(defaultValue = "false") boolean fail) throws InterruptedException {
        long clamped = Math.min(Math.max(delayMs, 0), 30_000);
        Thread.sleep(clamped);
        if (fail) {
            // 模拟下游报错（演练错误传播时的备用开关）
            throw new IllegalStateException("mock downstream internal error");
        }
        return Map.of("service", "mock-downstream", "tookMs", clamped);
    }
}
