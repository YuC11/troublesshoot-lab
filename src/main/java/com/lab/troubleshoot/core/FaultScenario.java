package com.lab.troubleshoot.core;

import java.util.Map;

/**
 * 故障场景策略契约 —— <b>Strategy 策略模式</b>。
 *
 * <p>每一种线上故障（堆 OOM、死锁、慢 SQL……）都是一种可独立插拔的「策略」。
 * 控制层（{@code FaultController}）只依赖本接口，不认识任何具体故障——
 * 新增场景 = 新增一个实现类，Spring 自动装配，REST 与控制台零改动（开闭原则）。</p>
 *
 * <p>注意：实现类<b>不应</b>直接实现本接口，而应继承
 * {@link AbstractFaultScenario}（模板方法已固化生命周期与状态守卫）。</p>
 */
public interface FaultScenario {

    /** 全局唯一 ID，出现在 REST 路径与控制台中，如 {@code oom-heap} */
    String id();

    /** 场景名称，如「堆内存 OOM：无界缓存」 */
    String name();

    /** 所属分类 */
    FaultCategory category();

    /** 演练顺序（01~16），与 docs/ 目录的 Runbook 编号一致 */
    int order();

    /** 模拟线上告警文案（还原真实告警的措辞与噪声，训练「从告警读信息」的能力） */
    String alertText();

    /** 现象与教学说明（展示在控制台卡片上） */
    String symptom();

    /** 对应的 Runbook 文档文件名（相对 docs/），控制台直接链接 */
    String docFile();

    /**
     * 触发故障的具体逻辑（模板方法 {@code AbstractFaultScenario#trigger} 的钩子）。
     *
     * @param params REST 传入的可选参数（如 crash 模式、强度），允许为空 Map
     */
    void doTrigger(Map<String, String> params) throws Exception;

    /** 停止并清理故障（模板方法 {@code AbstractFaultScenario#stop} 的钩子） */
    void doStop() throws Exception;

    /** 构建教学性实时状态数据（已泄漏 MB、句柄数、活跃线程数等），展示在控制台与 status 接口 */
    Map<String, Object> buildStatus();
}
