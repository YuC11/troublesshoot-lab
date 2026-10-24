package com.lab.troubleshoot.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 故障场景状态机（State 模式的枚举简化实现）。
 *
 * <p>合法流转：READY → RUNNING → READY；任何状态出错进入 ERROR，
 * ERROR 在 stop() 复位后回到 READY。用枚举 + CAS 保证并发下的状态一致性，
 * 避免引入完整 State 模式的类层级——「用最轻的方案解决问题」本身就是工程判断。</p>
 */
@Getter
@RequiredArgsConstructor
public enum FaultState {

    READY("就绪，可触发"),
    RUNNING("故障运行中"),
    ERROR("故障已造成实际损害（如 OOM/句柄打满），stop 后可复位");

    private final String label;
}
