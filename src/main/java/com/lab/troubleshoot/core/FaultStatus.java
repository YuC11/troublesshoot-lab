package com.lab.troubleshoot.core;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * 故障场景状态快照 —— <b>Builder 模式</b>（Lombok {@code @Builder} 生成器实现）。
 *
 * <p>{@code @Value} 使其成为不可变值对象：状态快照一旦生成就不会被并发修改，
 * 可以安全地直接序列化为 JSON 返回给控制台（不可变 DTO 是并发环境下的最佳实践）。</p>
 */
@Value
@Builder
public class FaultStatus {

    String id;
    String name;
    FaultCategory category;
    String stateLabel;
    FaultState state;
    int order;

    /** 模拟线上告警文案 */
    String alertText;
    /** 现象与教学说明 */
    String symptom;
    /** 对应 Runbook 文件 */
    String docFile;

    /** 当前进程 PID（演练者可直接复制进 jstack/jmap 命令） */
    long pid;
    /** ERROR 状态下的原因描述 */
    String error;

    /** 各场景自定义的实时教学数据（已泄漏 MB / 句柄数 / FGC 次数...） */
    Map<String, Object> details;

    long timestamp;
}
