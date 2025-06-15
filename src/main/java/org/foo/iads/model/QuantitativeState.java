// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/model/QuantitativeState.java
// 作用: 存储和计算定量指标（如RTT, PLR）的状态。
// =====================================================================
package org.foo.iads.model;

import org.apache.commons.math3.stat.descriptive.moment.Variance;
import java.util.LinkedList;
import java.util.Queue;

/**
 * 定量指标（如RTT, PLR）的状态。
 * 使用高斯分布来建模，通过均值(mu)和方差(sigma2)来表示。
 * 同时维护一个滑动窗口来计算稳定性。
 */
public class QuantitativeState implements MetricState {

    private static final long serialVersionUID = -89234798234789L;
    private static final int SLIDING_WINDOW_SIZE = 20; // 用于计算稳定性的滑动窗口大小

    // 高斯分布参数
    private double mu;      // 均值
    private double sigma2;  // 方差

    private long lastUpdateTime;
    private double probeInterval; // 单位：秒

    // 用于计算稳定性的滑动窗口
    private final Queue<Double> measurements = new LinkedList<>();

    public QuantitativeState() {
        reset();
    }

    @Override
    public void reset() {
        // 初始状态，假设一个非常不确定的先验分布
        this.mu = 0.0;
        this.sigma2 = 1000.0; // 较大的初始方差表示高不确定性
        this.lastUpdateTime = System.currentTimeMillis();
        this.probeInterval = 10.0; // 默认探测间隔10秒
        this.measurements.clear();
    }

    /**
     * 根据新的测量值更新高斯分布参数（贝叶斯更新）。
     *
     * @param value       新的测量值
     * @param noiseSigma2 测量噪声的方差
     */
    public void update(double value, double noiseSigma2) {
        // 贝叶斯更新公式
        double newSigma2 = 1.0 / (1.0 / this.sigma2 + 1.0 / noiseSigma2);
        this.mu = newSigma2 * (this.mu / this.sigma2 + value / noiseSigma2);
        this.sigma2 = newSigma2;

        // 更新滑动窗口和时间
        if (measurements.size() >= SLIDING_WINDOW_SIZE) {
            measurements.poll();
        }
        measurements.offer(value);
        this.lastUpdateTime = System.currentTimeMillis();
    }


    @Override
    public double getUncertainty() {
        // 使用高斯分布的微分熵公式计算不确定性
        if (sigma2 <= 0) {
            return 0;
        }
        return 0.5 * Math.log(2 * Math.PI * Math.E * sigma2);
    }

    @Override
    public double getStability() {
        // 使用滑动窗口内测量值的方差作为稳定性
        if (measurements.size() < 2) {
            return 0.0; // 数据点太少，无法计算方差
        }
        double[] values = measurements.stream().mapToDouble(d -> d).toArray();
        Variance variance = new Variance();
        return variance.evaluate(values);
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

    public double getMu() {
        return mu;
    }

    public double getSigma2() {
        return sigma2;
    }
}