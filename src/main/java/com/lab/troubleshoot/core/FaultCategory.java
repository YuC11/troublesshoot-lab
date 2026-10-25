package com.lab.troubleshoot.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 故障场景四大分类 —— 与 docs/01-排查方法论与工具优先级.md 的问题全景一一对应。
 */
@Getter
@RequiredArgsConstructor
public enum FaultCategory {

    MEMORY("内存类", "OOM / 内存泄漏 / GC 异常"),
    CPU("CPU 类", "业务死循环 / 回溯风暴 / 框架开销"),
    THREAD_IO("线程与 IO 类", "死锁 / 线程池耗尽 / 句柄泄漏"),
    SLOW_API("接口慢类", "慢 SQL / 慢外部依赖 / 锁竞争 / 连接池耗尽");

    private final String label;
    private final String hint;
}
