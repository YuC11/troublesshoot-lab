package com.lab.troubleshoot.web;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultScenarioRegistry;
import com.lab.troubleshoot.core.FaultState;
import com.lab.troubleshoot.core.FaultStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 故障演练统一门面 —— <b>Facade 门面模式</b>。
 *
 * <p>REST 层不认识任何具体故障：全部请求经注册中心按 ID 路由到策略实现。
 * 17 个场景共用 trigger/stop/status 三端点的「一套 API 治理全部故障」（另附盲测 quiz/reveal 两端点），控制台因此可以用统一组件渲染所有卡片。</p>
 *
 * <p>API 一览：</p>
 * <ul>
 *   <li>GET  /api/faults                 全部场景状态（含教学元数据）</li>
 *   <li>POST /api/faults/{id}/trigger    触发（可选 query 参数透传给场景，如 ?mode=crash）</li>
 *   <li>POST /api/faults/{id}/stop       停止并复位</li>
 *   <li>GET  /api/faults/{id}/status     单场景实时状态</li>
 *   <li>POST /api/faults/quiz            盲测模式：随机触发一个空闲场景，只返回告警不透露身份</li>
 *   <li>GET  /api/faults/quiz/reveal     揭晓盲测答案（返回被测场景完整状态）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/faults")
@RequiredArgsConstructor
public class FaultController {

    private final FaultScenarioRegistry registry;

    /** 全部场景状态快照（控制台首屏数据源） */
    @GetMapping
    public List<FaultStatus> listAll() {
        return registry.all().stream().map(s -> s.statusSnapshot()).toList();
    }

    /**
     * 触发故障。query 参数原样透传（如 oom-heap?mode=crash），
     * 由各场景自行声明与解析自己关心的参数。
     */
    @PostMapping("/{id}/trigger")
    public FaultStatus trigger(@PathVariable String id, @RequestParam Map<String, String> params) {
        var scenario = require(id);
        try {
            scenario.trigger(params);
        } catch (IllegalStateException e) {
            // 场景已运行 → 409 冲突（REST 语义化状态码）
            throw new ScenarioException(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            throw new ScenarioException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "触发失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return scenario.statusSnapshot();
    }

    /** 停止故障并复位到 READY */
    @PostMapping("/{id}/stop")
    public FaultStatus stop(@PathVariable String id) {
        var scenario = require(id);
        scenario.stop();
        return scenario.statusSnapshot();
    }

    /** 单场景实时状态（控制台轮询运行中的场景） */
    @GetMapping("/{id}/status")
    public FaultStatus status(@PathVariable String id) {
        return require(id).statusSnapshot();
    }

    // ==================== 盲测模式（训练「只看告警判断故障」的实战直觉） ====================

    /** 当前盲测的场景 id；null = 无进行中的盲测 */
    private final AtomicReference<String> quizId = new AtomicReference<>();

    /**
     * 盲测模式：随机触发一个空闲场景，只返回告警文本与分类——不透露 id/名称/详情。
     * 与「一次只触发一个场景」共用状态机（被测场景进入 RUNNING 后其余场景照常可看不可比）。
     */
    @PostMapping("/quiz")
    public synchronized Map<String, Object> quiz() {
        String prev = quizId.get();
        if (prev != null) {
            FaultState prevstate = registry.find(prev).map(s -> s.statusSnapshot().getState()).orElse(null);
            if (prevstate == FaultState.RUNNING) {
                throw new ScenarioException(HttpStatus.CONFLICT, "盲测进行中，先 GET /api/faults/quiz/reveal 揭晓");
            }
            quizId.compareAndSet(prev, null); // 上局已被手动停止，自动收尾
        }
        List<AbstractFaultScenario> ready = registry.all().stream()
                .filter(s -> s.statusSnapshot().getState() == FaultState.READY)
                .toList();
        if (ready.isEmpty()) {
            throw new ScenarioException(HttpStatus.CONFLICT, "没有空闲场景可出题");
        }
        var target = ready.get(ThreadLocalRandom.current().nextInt(ready.size()));
        try {
            target.trigger(Map.of());
        } catch (IllegalStateException e) {
            throw new ScenarioException(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            throw new ScenarioException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "盲测触发失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        quizId.set(target.id());
        log.info("[盲测] 已出题（身份保密），分类={}，共 {} 个候选", target.category(), ready.size());
        var snap = target.statusSnapshot();
        return Map.of(
                "quizActive", true,
                "category", snap.getCategory().name(),
                "alertText", snap.getAlertText(),
                "pid", snap.getPid());
    }

    /** 揭晓盲测答案：返回被测场景完整状态并结束盲测（场景仍 RUNNING，排查/停止照常） */
    @GetMapping("/quiz/reveal")
    public FaultStatus quizReveal() {
        String id = quizId.getAndSet(null);
        if (id == null) {
            throw new ScenarioException(HttpStatus.NOT_FOUND, "没有进行中的盲测");
        }
        log.info("[盲测] 已揭晓: {}", id);
        return require(id).statusSnapshot();
    }

    private com.lab.troubleshoot.core.AbstractFaultScenario require(String id) {
        return registry.find(id).orElseThrow(() ->
                new ScenarioException(HttpStatus.NOT_FOUND, "未知故障场景: " + id + "，GET /api/faults 查看全部"));
    }

    /** 演练场统一业务异常：携带 HTTP 状态码，由下方 Handler 统一渲染 */
    public static class ScenarioException extends RuntimeException {
        private final HttpStatus status;

        ScenarioException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** 把场景异常渲染成 {code, message}——线上 API 错误响应的规范形态 */
    @RestControllerAdvice
    static class ScenarioExceptionHandler {
        @ExceptionHandler(ScenarioException.class)
        ResponseEntity<Map<String, Object>> handle(ScenarioException e) {
            return ResponseEntity.status(e.status).body(Map.of(
                    "code", e.status.value(),
                    "message", e.getMessage()));
        }
    }
}
