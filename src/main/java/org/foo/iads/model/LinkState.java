// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/model/LinkState.java
// 作用: 封装了单条链路的所有指标状态，是ESM中存储的核心对象。
// =====================================================================
package org.foo.iads.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 代表一条链路的完整状态。
 * 包含该链路所有受监控指标（Liveness, RTT, PLR等）的各自状态。
 * 这个对象将作为值存储在 ESM 的 ConsistentMap 中。
 */
public class LinkState implements Serializable {

    private static final long serialVersionUID = 1L;

    // 使用HashMap来存储每个指标对应的状态（避免Kryo序列化EnumMap问题）
    private final Map<Metric, MetricState> metricStates = new HashMap<>();

    /**
     * 构造函数，初始化所有支持的指标状态。
     */
    public LinkState() {
        metricStates.put(Metric.LIVENESS, new LivenessState());
        metricStates.put(Metric.RTT, new QuantitativeState());
        metricStates.put(Metric.PLR, new QuantitativeState());
        // BANDWIDTH 暂不实现，可以后续添加
        // metricStates.put(Metric.BANDWIDTH, new QuantitativeState());
    }

    /**
     * 获取指定指标的状态对象。
     *
     * @param metric 指标类型
     * @return 对应的指标状态对象，如果不支持则为null
     */
    public MetricState getMetricState(Metric metric) {
        return metricStates.get(metric);
    }

    /**
     * 重置所有指标为初始状态。
     */
    public void resetAll() {
        metricStates.values().forEach(MetricState::reset);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("LinkState{\n");
        for (Map.Entry<Metric, MetricState> entry : metricStates.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": H=")
              .append(String.format("%.3f", entry.getValue().getUncertainty()))
              .append(", S=").append(String.format("%.3f", entry.getValue().getStability()))
              .append(", T_probe=").append(String.format("%.1fs", entry.getValue().getProbeInterval()))
              .append("\n");
        }
        sb.append("}");
        return sb.toString();
    }
}