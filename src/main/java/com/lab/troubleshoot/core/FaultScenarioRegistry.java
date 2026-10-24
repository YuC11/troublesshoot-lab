package com.lab.troubleshoot.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 故障场景注册中心 —— <b>Registry 注册器模式</b>。
 *
 * <p>Spring 会把容器中所有 {@link AbstractFaultScenario} Bean 按类型注入 List，
 * 本类在构造时收集为「ID → 场景」的索引。价值有二：
 * 控制层通过注册中心按 ID 路由（而不是 if-else 认识每个场景）；
 * 新增场景类即可被自动发现，注册动作零代码（这对 16 个场景的扩展性至关重要）。</p>
 */
@Slf4j
@Component
public class FaultScenarioRegistry {

    /** LinkedHashMap 保持插入序 = 按 order() 排序后的 01~16 展示序 */
    private final Map<String, AbstractFaultScenario> scenarios = new LinkedHashMap<>();

    public FaultScenarioRegistry(List<AbstractFaultScenario> discovered) {
        discovered.stream()
                .sorted(Comparator.comparingInt(FaultScenario::order))
                .forEach(s -> {
                    AbstractFaultScenario prev = scenarios.put(s.id(), s);
                    if (prev != null) {
                        // 场景 ID 重复属于开发期错误，快速失败暴露问题
                        throw new IllegalStateException("故障场景 ID 重复: " + s.id());
                    }
                });
        log.info("[注册中心] 已注册 {} 个故障场景: {}", scenarios.size(), scenarios.keySet());
    }

    /** 全部场景（按演练顺序 01~16） */
    public List<AbstractFaultScenario> all() {
        return List.copyOf(scenarios.values());
    }

    /** 按 ID 查找场景 */
    public Optional<AbstractFaultScenario> find(String id) {
        return Optional.ofNullable(scenarios.get(id));
    }
}
