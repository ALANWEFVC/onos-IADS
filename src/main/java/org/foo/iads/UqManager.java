// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/UqManager.java
// 作用: 实现IADS框架的不确定性量化器(UQ)。
//       负责为每个潜在的探测任务计算其预期信息增益(EIG)。
// =====================================================================
package org.foo.iads;

import org.foo.iads.model.LivenessState;
import org.foo.iads.model.Metric;
import org.foo.iads.model.ProbeTask;
import org.foo.iads.model.QuantitativeState;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IADS不确定性量化器(UQ)的实现。
 * <p>
 * 该组件的核心功能是量化探测任务的潜在价值。它通过计算每个任务的
 * 预期信息增益(EIG)来实现。EIG衡量了一次探测预期能为系统带来
 * 多大的不确定性降低量。
 */
@Component(immediate = true, service = UqManager.class)
public class UqManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    // 引用ESM来获取状态数据
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EsmManager esmManager;

    @Activate
    protected void activate() {
        log.info("IADS UQ - 不确定性量化器已启动。");
    }

    @Deactivate
    protected void deactivate() {
        log.info("IADS UQ - 不确定性量化器已停止。");
    }

    /**
     * 为当前网络中的所有可能探测任务计算EIG。
     *
     * @return 一个Map，Key为探测任务，Value为该任务的EIG值。
     */
    public Map<ProbeTask, Double> calculateAllEigs() {
        Map<ProbeTask, Double> eigs = new ConcurrentHashMap<>();

        // 遍历ESM中所有被跟踪的链路
        esmManager.getTrackedLinks().parallelStream().forEach(link -> {
            // 为每个链路的每个指标计算EIG
            for (Metric metric : Metric.values()) {
                if (esmManager.getOrCreateLinkState(link).getMetricState(metric) == null) {
                    continue;
                }
                ProbeTask task = new ProbeTask(link, metric);
                double eig = calculateEigForTask(task);
                eigs.put(task, eig);
            }
        });
        log.debug("完成了 {} 个任务的EIG计算。", eigs.size());
        return eigs;
    }

    /**
     * 为单个探测任务计算EIG。
     *
     * @param task 目标探测任务
     * @return EIG值
     */
    private double calculateEigForTask(ProbeTask task) {
        if (task.getMetric() == Metric.LIVENESS) {
            return calculateLivenessEig(task);
        } else {
            return calculateQuantitativeEig(task);
        }
    }

    /**
     * 计算Liveness指标的EIG。
     * EIG = H_before - E[H_after]
     * E[H_after] = P(up) * H_after(up) + P(down) * H_after(down)
     *
     * @param task Liveness探测任务
     * @return EIG值
     */
    private double calculateLivenessEig(ProbeTask task) {
        LivenessState state = (LivenessState) esmManager.getOrCreateLinkState(task.getLink())
                .getMetricState(Metric.LIVENESS);

        double hBefore = state.getUncertainty();

        // 探测后有两种可能结果：UP 或 DOWN
        // 1. 假设探测结果为UP
        double pUp = state.getProbabilityUp();
        double hAfterUp = calculateLivenessEntropy(state.getAlpha() + 1, state.getBeta());

        // 2. 假设探测结果为DOWN
        double pDown = 1 - pUp;
        double hAfterDown = calculateLivenessEntropy(state.getAlpha(), state.getBeta() + 1);

        // 3. 计算预期熵
        double expectedHAfter = pUp * hAfterUp + pDown * hAfterDown;

        return hBefore - expectedHAfter;
    }

    /**
     * 计算定量指标（RTT, PLR）的EIG。
     * EIG = H_before - H_after
     * (因为对于高斯分布，后验熵不依赖于测量值本身，所以E[H_after] = H_after)
     *
     * @param task 定量探测任务
     * @return EIG值
     */
    private double calculateQuantitativeEig(ProbeTask task) {
        QuantitativeState state = (QuantitativeState) esmManager.getOrCreateLinkState(task.getLink())
                .getMetricState(task.getMetric());

        if (state == null) {
            return 0.0;
        }

        double hBefore = state.getUncertainty();

        // 根据贝叶斯更新公式计算探测后的新方差
        double currentSigma2 = state.getSigma2();
        // 假设测量噪声方差为1.0
        double noiseSigma2 = 1.0;
        double newSigma2 = 1.0 / (1.0 / currentSigma2 + 1.0 / noiseSigma2);

        // 计算探测后的熵
        double hAfter = 0.5 * Math.log(2 * Math.PI * Math.E * newSigma2);

        return hBefore - hAfter;
    }

    /**
     * Liveness熵计算的辅助函数。
     *
     * @param alpha Beta分布的alpha参数
     * @param beta  Beta分布的beta参数
     * @return 熵值
     */
    private double calculateLivenessEntropy(double alpha, double beta) {
        double total = alpha + beta;
        double p = alpha / total;
        if (p <= 0 || p >= 1) {
            return 0;
        }
        return -(p * Math.log(p) + (1 - p) * Math.log(1 - p));
    }
}