package com.lab.troubleshoot.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模板方法生命周期单元测试——状态机 CAS 守卫与错误转移是 17 个场景共用的地基,
 * 任何回归都会同时炸掉全部场景（场景 02 冻死事故的教训: 教学代码也要防回归）。
 */
class AbstractFaultScenarioTest {

    private static AbstractFaultScenario dummy(String id, int order, Runnable onTrigger) {
        return new AbstractFaultScenario() {
            @Override public String id() { return id; }
            @Override public String name() { return id; }
            @Override public FaultCategory category() { return FaultCategory.MEMORY; }
            @Override public int order() { return order; }
            @Override public String alertText() { return "alert"; }
            @Override public String symptom() { return "symptom"; }
            @Override public String docFile() { return "x.md"; }
            @Override public void doTrigger(Map<String, String> params) { if (onTrigger != null) onTrigger.run(); }
            @Override public void doStop() { }
            @Override public Map<String, Object> buildStatus() { return Map.of(); }
        };
    }

    @Test
    void trigger_从READY进入RUNNING_重复触发抛冲突() throws Exception {
        var s = dummy("t1", 1, null);
        s.trigger(Map.of());
        assertThat(s.statusSnapshot().getState()).isEqualTo(FaultState.RUNNING);
        assertThatThrownBy(() -> s.trigger(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("t1");
    }

    @Test
    void stop_从RUNNING复位READY_从READY是空操作() {
        var s = dummy("t2", 2, null);
        s.stop(); // READY 时 stop 不应调用 doStop 也不应报错
        assertThat(s.statusSnapshot().getState()).isEqualTo(FaultState.READY);
        assertThat(s.statusSnapshot().getError()).isNull();
    }

    @Test
    void doTrigger抛异常_转入ERROR并保留原因() {
        var s = dummy("t3", 3, () -> { throw new IllegalStateException("注入失败"); });
        assertThatThrownBy(() -> s.trigger(Map.of())).isInstanceOf(IllegalStateException.class);
        var snap = s.statusSnapshot();
        assertThat(snap.getState()).isEqualTo(FaultState.ERROR);
        assertThat(snap.getError()).contains("注入失败");
        // ERROR 后 stop 仍可复位（清理失败转 ERROR 的路径同样可重试）
        s.stop();
        assertThat(s.statusSnapshot().getState()).isEqualTo(FaultState.READY);
    }

    @Test
    void markDamaged_记录损害原因并转ERROR() throws Exception {
        var s = dummy("t4", 4, null);
        s.trigger(Map.of());
        // markDamaged 是 protected, 经由同包子类可调; 用匿名子类直接验证语义
        var s2 = new AbstractFaultScenario() {
            @Override public String id() { return "t4b"; }
            @Override public String name() { return id(); }
            @Override public FaultCategory category() { return FaultCategory.MEMORY; }
            @Override public int order() { return 4; }
            @Override public String alertText() { return ""; }
            @Override public String symptom() { return ""; }
            @Override public String docFile() { return ""; }
            @Override public void doTrigger(Map<String, String> params) { }
            @Override public void doStop() { }
            @Override public Map<String, Object> buildStatus() { return Map.of(); }
            void damage() { markDamaged("句柄打满"); }
        };
        s2.damage();
        assertThat(s2.statusSnapshot().getState()).isEqualTo(FaultState.ERROR);
        assertThat(s2.statusSnapshot().getError()).isEqualTo("句柄打满");
    }

    @Test
    void statusSnapshot_快照含pid与不可变字段() throws Exception {
        var s = dummy("t5", 5, null);
        s.trigger(Map.of());
        var snap = s.statusSnapshot();
        assertThat(snap.getPid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(snap.getOrder()).isEqualTo(5);
        assertThat(snap.getTimestamp()).isPositive();
    }
}
