package com.lab.troubleshoot.core;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 故障场景生命周期骨架 —— <b>Template Method 模板方法模式</b>。
 *
 * <p>「状态校验 → 触发 → 登记 RUNNING」与「停止 → 复位 READY」的生命周期对 16 个场景
 * 完全一致，因此固化在父类 final 方法中；子类只关心<b>怎么制造/清理故障</b>
 * （doTrigger / doStop / buildStatus 三个钩子）——模板方法让「流程代码只写一遍」。</p>
 *
 * <p>线程安全：状态用 CAS 推进，防止控制台并发双击触发同一场景；
 * 重复触发返回友好错误而不是把堆栈打给用户（线上 API 的基本修养）。</p>
 */
@Slf4j
public abstract class AbstractFaultScenario implements FaultScenario {

    /** 场景状态；volatile 语义由 AtomicReference 提供 */
    private final AtomicReference<FaultState> state = new AtomicReference<>(FaultState.READY);

    /** 最近一次错误描述（ERROR 状态时展示给演练者） */
    private volatile String lastError;

    // ==================== 模板方法（生命周期，final 固化） ====================

    /**
     * 触发故障（模板方法）：READY→RUNNING CAS 守卫，异常时转入 ERROR。
     *
     * @throws IllegalStateException 场景已在运行（对应 HTTP 409）
     */
    public final void trigger(Map<String, String> params) throws Exception {
        if (!state.compareAndSet(FaultState.READY, FaultState.RUNNING)) {
            throw new IllegalStateException("场景 [%s] 当前状态为 %s，请先 stop 复位再触发".formatted(id(), state.get()));
        }
        log.info("[场景触发] {} ({}) params={}", name(), id(), params);
        try {
            doTrigger(params == null ? Map.of() : params);
        } catch (Exception e) {
            state.set(FaultState.ERROR);
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("[场景出错] {} —— {}", id(), lastError, e);
            throw e;
        }
    }

    /** 停止故障（模板方法）：任何状态都可复位，清理失败转入 ERROR 并保留原因 */
    public final void stop() {
        if (state.get() == FaultState.READY) {
            return;
        }
        log.info("[场景停止] {} ({})", name(), id());
        try {
            doStop();
            state.set(FaultState.READY);
            lastError = null;
        } catch (Exception e) {
            state.set(FaultState.ERROR);
            lastError = "清理失败: " + e.getMessage();
            log.error("[场景清理失败] {}", id(), e);
        }
    }

    /** 供异步线程（如句柄打满时）主动把场景标记为「已造成实际损害」 */
    protected final void markDamaged(String reason) {
        lastError = reason;
        state.set(FaultState.ERROR);
    }

    /** 生成完整状态快照（Builder 模式，见 {@link FaultStatus}） */
    public final FaultStatus statusSnapshot() {
        return FaultStatus.builder()
                .id(id())
                .name(name())
                .category(category())
                .state(state.get())
                .stateLabel(state.get().getLabel())
                .order(order())
                .alertText(alertText())
                .symptom(symptom())
                .docFile(docFile())
                .pid(pid())
                .error(lastError)
                .details(buildStatus())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // ==================== 供子类复用的工具 ====================

    /** 当前进程 PID —— 写进 status，让演练者直接复制进 jstack/jmap 命令 */
    protected final long pid() {
        return ProcessHandle.current().pid();
    }

    /** 读取整型参数，缺省用 defaultValue（参数解析失败按缺省处理，保持演练容错） */
    protected final int intParam(Map<String, String> params, String key, int defaultValue) {
        try {
            String v = params.get(key);
            return v == null ? defaultValue : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("参数 {}={} 不是数字, 使用缺省值 {}", key, params.get(key), defaultValue);
            return defaultValue;
        }
    }

    /** 读取字符串参数 */
    protected final String strParam(Map<String, String> params, String key, String defaultValue) {
        String v = params.get(key);
        return (v == null || v.isBlank()) ? defaultValue : v.trim();
    }
}
