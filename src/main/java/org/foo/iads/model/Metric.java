// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/model/Metric.java
// 作用: 定义了IADS系统支持的探测指标类型。
// =====================================================================
package org.foo.iads.model;

/**
 * 探测指标的枚举类型。
 * IADS框架关注网络链路的多种性能指标。
 */
public enum Metric {
    /**
     * 存活状态 (Liveness)
     * 用于判断链路是否处于UP状态。
     */
    LIVENESS,

    /**
     * 往返时延 (Round-Trip Time)
     * 测量数据包在链路上往返所需的时间。
     */
    RTT,

    /**
     * 丢包率 (Packet Loss Rate)
     * 测量在链路上丢失数据包的比例。
     */
    PLR,

    /**
     * 带宽 (Bandwidth)
     * (暂未在核心逻辑中实现，作为未来扩展保留)
     * 测量链路的可用吞吐量。
     */
    BANDWIDTH
}