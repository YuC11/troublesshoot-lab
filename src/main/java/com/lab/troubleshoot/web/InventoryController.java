package com.lab.troubleshoot.web;

import com.lab.troubleshoot.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 库存接口 —— 场景 15「锁竞争」的用户体验入口。
 * 场景未触发时 ~2s（锁内 sleep 本身就慢）；触发后被 40 个后台线程抢锁，
 * 排队时间肉眼可见地恶化。
 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/deduct")
    public Map<String, Object> deduct(@RequestParam(defaultValue = "2000") long holdMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        // 真正的等待发生在进入 synchronized 方法之前——BLOCKED 的就是这批请求线程
        inventoryService.deductWithWideLock(holdMs);
        long cost = System.currentTimeMillis() - start;
        return Map.of(
                "result", "扣减成功",
                "costMs", cost,
                "queuedMs", Math.max(cost - holdMs, 0), // 排队时间 = 总耗时 - 锁内耗时
                "totalDeductions", inventoryService.totalDeductions());
    }
}
