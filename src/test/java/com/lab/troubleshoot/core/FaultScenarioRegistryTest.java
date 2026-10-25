package com.lab.troubleshoot.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 注册中心单元测试：ID 去重快速失败 + 按 order 排序（控制台展示序的保证） */
class FaultScenarioRegistryTest {

    private static AbstractFaultScenario dummy(String id, int order) {
        return new AbstractFaultScenario() {
            @Override public String id() { return id; }
            @Override public String name() { return id; }
            @Override public FaultCategory category() { return FaultCategory.CPU; }
            @Override public int order() { return order; }
            @Override public String alertText() { return ""; }
            @Override public String symptom() { return ""; }
            @Override public String docFile() { return ""; }
            @Override public void doTrigger(Map<String, String> params) { }
            @Override public void doStop() { }
            @Override public Map<String, Object> buildStatus() { return Map.of(); }
        };
    }

    @Test
    void 收集场景_按order排序输出() {
        var registry = new FaultScenarioRegistry(List.of(
                dummy("c", 3), dummy("a", 1), dummy("b", 2)));
        assertThat(registry.all()).extracting(AbstractFaultScenario::id)
                .containsExactly("a", "b", "c");
        assertThat(registry.find("b")).isPresent();
        assertThat(registry.find("zz")).isEmpty();
    }

    @Test
    void ID重复_构造期快速失败() {
        assertThatThrownBy(() -> new FaultScenarioRegistry(List.of(
                dummy("dup", 1), dummy("dup", 2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }
}
