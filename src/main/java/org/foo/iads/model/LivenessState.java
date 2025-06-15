// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/model/LivenessState.java
// 作用: 存储和计算Liveness指标的状态。
// =====================================================================
package org.foo.iads.model;

/**
 * Liveness指标的状态。
 * 使用Beta分布来建模，通过alpha和beta参数表示UP/DOWN的信念。
 */
public class LivenessState implements MetricState {

    private static final long serialVersionUID = -273894782349812L;

    // Beta分布参数，alpha代表成功的次数(UP)，beta代表失败的次数(DOWN)
    private double alpha;
    private double beta;

    private double stability; // Liveness 的稳定性暂不计算，保留字段
    private long lastUpdateTime;
    private double probeInterval; // 单位：秒

    public LivenessState() {
        reset();
    }

    @Override
    public void reset() {
        // 初始状态，假设先验知识为一次成功和一次失败，P(UP)=0.5
        this.alpha = 1.0;
        this.beta = 1.0;
        this.stability = 0.0; // 稳定性初始为0
        this.lastUpdateTime = System.currentTimeMillis();
        this.probeInterval = 10.0; // 默认探测间隔10秒
    }

    /**
     * 根据探测结果更新状态。
     * @param isUp 探测结果是否为UP
     */
    public void update(boolean isUp) {
        if (isUp) {
            this.alpha++;
        } else {
            this.beta++;
        }
        this.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * 计算Liveness的置信度 P(UP)。
     * @return P(UP) 的值
     */
    public double getProbabilityUp() {
        return alpha / (alpha + beta);
    }

    @Override
    public double getUncertainty() {
        // 使用二元熵公式计算不确定性
        double p = getProbabilityUp();
        if (p <= 0 || p >= 1) {
            return 0;
        }
        return -(p * Math.log(p) + (1 - p) * Math.log(1 - p));
    }

    @Override
    public double getStability() {
        // Liveness 的稳定性比较特殊，可以定义为 P(UP) 偏离 0.5 的程度
        // 这里暂时简化，返回0
        return stability;
    }

    @Override
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    @Override
    public double getProbeInterval() {
        return probeInterval;
    }

    @Override
    public void setProbeInterval(double interval) {
        this.probeInterval = interval;
    }

    public double getAlpha() {
        return alpha;
    }

    public double getBeta() {
        return beta;
    }
}