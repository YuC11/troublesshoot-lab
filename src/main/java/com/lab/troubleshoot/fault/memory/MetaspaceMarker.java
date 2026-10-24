package com.lab.troubleshoot.fault.memory;

/**
 * Metaspace 演练用的标记类。
 *
 * <p>设计约束：<b>必须是最小依赖的空壳类</b>（不引用任何其他类）——因为场景 02 会把它的
 * .class 字节码读出来，在成百上千个不同的 ClassLoader 里反复 define。
 * 类越小，单份元数据占用越可控，Metaspace 增长越线性、越适合观察。</p>
 */
public class MetaspaceMarker {
}
