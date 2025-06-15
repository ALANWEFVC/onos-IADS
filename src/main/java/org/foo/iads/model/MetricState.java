// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/model/MetricState.java
// 作用: 定义了指标状态的通用接口，便于统一管理。
// =====================================================================
package org.foo.iads.model;

import java.io.Serializable;

/**
 * 指标状态的通用接口。
 * 所有具体的指标状态类（如LivenessState, QuantitativeState）都实现此接口。
 * 必须实现 Serializable 接口，以便能在 ONOS 的分布式存储中序列化。
 */
public interface MetricState extends Serializable {

    /**
     * 获取当前指标的不确定性 (H)。
     * @return 不确定性值
     */
    double getUncertainty();

    /**
     * 获取当前指标的稳定性 (S)。
     * @return 稳定性值
     */
    double getStability();

    /**
     * 获取此指标状态的最后更新时间戳。
     * @return Unix时间戳 (毫秒)
     */
    long getLastUpdateTime();

    /**
     * 获取为此指标动态调整的探测间隔 (T_probe)。
     * @return 探测间隔 (秒)
     */
    double getProbeInterval();

    /**
     * 设置新的探测间隔。
     * @param interval 新的探测间隔 (秒)
     */
    void setProbeInterval(double interval);

    /**
     * 重置状态为初始默认值。
     */
    void reset();
}