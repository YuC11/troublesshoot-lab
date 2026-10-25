package com.lab.troubleshoot;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultScenarioRegistry;
import com.lab.troubleshoot.core.FaultState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全场景冒烟测试：trigger → 停留 → stop → 断言复位 READY（「停止即回落」契约的自动化守卫）。
 *
 * <p>运行前提：本机 MySQL 可达（演练数据已就绪）。用 JUnit 假设实现环境自适应——
 * DB 不可达时<b>跳过</b>而非失败（CI 无 DB 环境用 -DexcludedGroups=smoke 排除本类）。
 * 只测温和模式（不触发 crash OOM），每个场景独立 try/finally 保证清理。</p>
 */
@Tag("smoke")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScenarioSmokeTest {

    @Autowired FaultScenarioRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;

    private boolean dbReachable() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @TestFactory
    Stream<DynamicTest> 十七个场景触发停止复位() {
        Assumptions.assumeTrue(dbReachable(), "MySQL 不可达, 冒烟测试跳过(CI 用 -DexcludedGroups=smoke)");
        return registry.all().stream().map(s -> DynamicTest.dynamicTest(s.id(), () -> {
            Assumptions.assumeTrue(s.statusSnapshot().getState() == FaultState.READY, "非 READY(其他测试占用), 跳过");
            try {
                s.trigger(Map.of());
                Thread.sleep(700); // 让故障真实发生一拍
            } finally {
                s.stop();
            }
            var snap = s.statusSnapshot();
            assertThat(snap.getState())
                    .as("场景 %s stop 后应复位 READY, error=%s", s.id(), snap.getError())
                    .isEqualTo(FaultState.READY);
        }));
    }

    @Test
    void 盲测接口_出题不泄露身份_揭晓返回完整状态() {
        Assumptions.assumeTrue(dbReachable(), "MySQL 不可达, 冒烟测试跳过");
        // 先确保没有别的场景占着 RUNNING(动态测试已各自复位)
        var quiz = rest.postForObject("/api/faults/quiz", null, Map.class);
        assertThat(quiz).isNotNull();
        assertThat(quiz.get("quizActive")).isEqualTo(true);
        // 脱敏断言: 响应只允许这四个键, 不得含 id/name/details
        assertThat(quiz.keySet()).containsExactlyInAnyOrder("quizActive", "category", "alertText", "pid");
        var reveal = rest.getForObject("/api/faults/quiz/reveal", Map.class);
        assertThat(reveal).isNotNull();
        assertThat((String) reveal.get("id")).isNotBlank();
        // 收尾: 揭晓后场景仍 RUNNING, 手动停止复位
        // (reveal 返回 FaultStatus; 通过 registry 停止以保证幂等)
        registry.find((String) reveal.get("id")).ifPresent(AbstractFaultScenario::stop);
    }

    @Test
    void metrics_含committed撞墙口径字段() {
        var m = rest.getForObject("/api/metrics", Map.class);
        assertThat(m).containsKeys("metaspaceUsedMb", "metaspaceCommittedMb", "metaspaceMaxMb");
    }
}
