package com.lab.troubleshoot.web;

import com.lab.troubleshoot.core.FaultScenarioRegistry;
import com.lab.troubleshoot.core.FaultStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 故障演练统一门面 —— <b>Facade 门面模式</b>。
 *
 * <p>REST 层不认识任何具体故障：全部请求经注册中心按 ID 路由到策略实现。
 * 16 个场景共用 3 个端点的「一套 API 治理全部故障」，控制台因此可以用统一组件渲染所有卡片。</p>
 *
 * <p>API 一览：</p>
 * <ul>
 *   <li>GET  /api/faults                 全部场景状态（含教学元数据）</li>
 *   <li>POST /api/faults/{id}/trigger    触发（可选 query 参数透传给场景，如 ?mode=crash）</li>
 *   <li>POST /api/faults/{id}/stop       停止并复位</li>
 *   <li>GET  /api/faults/{id}/status     单场景实时状态</li>
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
