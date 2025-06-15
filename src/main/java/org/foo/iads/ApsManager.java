// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/ApsManager.java
// 作用: 实现IADS框架的主动探测调度器(APS)，是整个系统的决策核心。
//       集成了CMAB, CTLC, PRIO三个子模块。
// =====================================================================
package org.foo.iads;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.DecompositionSolver;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.distribution.MultivariateNormalDistribution;
import org.foo.iads.model.Metric;
import org.foo.iads.model.MetricState;
import org.foo.iads.model.ProbeTask;
import org.onosproject.net.Link;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IADS主动探测调度器(APS)的实现。
 * <p>
 * 这是IADS框架的大脑，负责根据当前网络状态和系统目标，
 * 智能地选择最有价值的Top-K个探测任务。
 */
@Component(immediate = true, service = ApsManager.class)
public class ApsManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    // --- APS 配置参数 ---
    private static final int CONTEXT_VECTOR_DIM = 4;
    private static final double THOMPSON_SAMPLING_SIGMA = 1.0; // 奖励噪声的标准差
    private static final double KP = 0.1; // CTLC的比例系数
    private static final double TARGET_STABILITY = 1.0; // CTLC的目标稳定性
    private static final double MIN_PROBE_INTERVAL = 1.0; // 最小探测间隔 (秒)
    private static final double MAX_PROBE_INTERVAL = 60.0; // 最大探测间隔 (秒)

    // PRIO 权重
    private static final double WEIGHT_EIG = 0.4;
    private static final double WEIGHT_URGENCY = 0.3;
    private static final double WEIGHT_POLICY_MATCH = 0.2;
    private static final double WEIGHT_EVENT_TRIG = 0.1;


    // --- ONOS 服务引用 ---
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EsmManager esmManager;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected UqManager uqManager;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EmManager emManager;


    // --- CMAB 子模块状态 ---
    private enum Policy {
        FOCUS_UNCERTAINTY,
        HIGH_FREQ_UNSTABLE,
        COVERAGE_BALANCER,
        EVENT_TRIGGER
    }
    private final Map<Policy, RealMatrix> cmabSigma = new EnumMap<>(Policy.class);
    private final Map<Policy, RealVector> cmabMu = new EnumMap<>(Policy.class);
    private Policy lastChosenPolicy;


    @Activate
    protected void activate() {
        initializeCmab();
        log.info("IADS APS - 主动探测调度器已启动。");
    }

    @Deactivate
    protected void deactivate() {
        log.info("IADS APS - 主动探测调度器已停止。");
    }

    /**
     * 初始化CMAB的参数。
     */
    private void initializeCmab() {
        for (Policy policy : Policy.values()) {
            // 初始均值向量为0
            cmabMu.put(policy, new ArrayRealVector(new double[CONTEXT_VECTOR_DIM]));
            // 初始协方差矩阵为单位矩阵
            cmabSigma.put(policy, new Array2DRowRealMatrix(createIdentityMatrix(CONTEXT_VECTOR_DIM)));
        }
        // 默认选择一个策略开始
        lastChosenPolicy = Policy.COVERAGE_BALANCER;
    }

    /**
     * 调度核心方法：执行一轮完整的APS决策流程，选出Top-K任务。
     *
     * @param k 要选择的任务数量
     * @return 优先级最高的k个探测任务列表
     */
    public List<ProbeTask> scheduleTopKTasks(int k) {
        log.info("APS开始新一轮调度...");

        // 1. 从ESM获取上下文向量
        double[] contextArray = esmManager.calculateContextVector(emManager.getNumRecentEvents());
        RealVector context = new ArrayRealVector(contextArray);

        // 2. CMAB: 选择当前最优策略
        Policy chosenPolicy = selectPolicy(context);
        this.lastChosenPolicy = chosenPolicy;
        log.info("CMAB选择的策略: {}", chosenPolicy);

        // 3. CTLC: 根据稳定性调整所有任务的探测间隔
        adjustAllProbeIntervals();

        // 4. PRIO: 计算所有任务的优先级并排序
        //    a. 获取所有任务的EIG
        Map<ProbeTask, Double> allEigs = uqManager.calculateAllEigs();
        Set<Link> trackedLinks = esmManager.getTrackedLinks();

        //    b. 为每个任务计算总优先级
        List<PrioritizedTask> prioritizedTasks = trackedLinks.parallelStream()
            .flatMap(link -> {
                // Create tasks for all metric types
                return java.util.Arrays.stream(Metric.values())
                    .map(metric -> new ProbeTask(link, metric));
            })
            .map(task -> {
                double priority = calculatePriority(task, allEigs.getOrDefault(task, 0.0), chosenPolicy);
                return new PrioritizedTask(task, priority);
            })
            .collect(Collectors.toList());

        //    c. 排序并选出Top-K
        prioritizedTasks.sort(Comparator.comparingDouble(PrioritizedTask::getPriority).reversed());

        List<ProbeTask> topKTasks = prioritizedTasks.stream()
                .limit(k)
                .map(PrioritizedTask::getTask)
                .collect(Collectors.toList());

        if (log.isDebugEnabled() && !topKTasks.isEmpty()) {
            log.debug("PRIO选出的Top-K任务 (k={}):", topKTasks.size());
            topKTasks.forEach(task -> {
                PrioritizedTask pt = prioritizedTasks.stream().filter(p -> p.getTask().equals(task)).findFirst().get();
                log.debug("  - {}, 优先级: {:.4f}", task, pt.getPriority());
            });
        }
        log.info("APS调度完成，选出 {} 个任务。", topKTasks.size());
        return topKTasks;
    }

    /**
     * CMAB：使用Thompson Sampling选择策略。
     */
    private Policy selectPolicy(RealVector context) {
        double maxScore = Double.NEGATIVE_INFINITY;
        Policy bestPolicy = null;

        for (Policy policy : Policy.values()) {
            RealMatrix sigma = cmabSigma.get(policy);
            RealVector mu = cmabMu.get(policy);

            // 从策略的后验分布 N(mu, sigma) 中采样一个参数向量 theta
            MultivariateNormalDistribution dist = new MultivariateNormalDistribution(mu.toArray(), sigma.getData());
            RealVector thetaSample = new ArrayRealVector(dist.sample());

            // 计算得分
            double score = context.dotProduct(thetaSample);

            if (score > maxScore) {
                maxScore = score;
                bestPolicy = policy;
            }
        }
        return bestPolicy;
    }

    /**
     * CTLC：调整所有链路所有指标的探测间隔。
     */
    private void adjustAllProbeIntervals() {
        log.debug("CTLC开始调整探测间隔...");
        int adjustedCount = 0;
        for (Link link : esmManager.getTrackedLinks()) {
            for (Metric metric : Metric.values()) {
                MetricState metricState = esmManager.getOrCreateLinkState(link).getMetricState(metric);
                if (metricState == null) continue;

                double oldInterval = metricState.getProbeInterval();
                double stability = metricState.getStability();

                // 应用控制理论公式
                double newInterval = oldInterval * (1 + KP * (1 - (stability / TARGET_STABILITY)));

                // 限制新间隔在[T_min, T_max]范围内
                newInterval = Math.max(MIN_PROBE_INTERVAL, Math.min(newInterval, MAX_PROBE_INTERVAL));

                if (Math.abs(newInterval - oldInterval) > 0.1) { // 仅在有显著变化时更新
                    esmManager.setProbeInterval(link, metric, newInterval);
                    adjustedCount++;
                }
            }
        }
        log.debug("CTLC调整了 {} 个任务的探测间隔。", adjustedCount);
    }


    /**
     * PRIO: 计算单个任务的优先级总分。
     */
    private double calculatePriority(ProbeTask task, double eig, Policy chosenPolicy) {
        MetricState metricState = esmManager.getOrCreateLinkState(task.getLink()).getMetricState(task.getMetric());
        if (metricState == null) {
            return 0.0;
        }

        // 1. Urgency: (currentTime - lastUpdate) / probeInterval
        double urgency = (System.currentTimeMillis() - metricState.getLastUpdateTime()) /
                         (metricState.getProbeInterval() * 1000.0);

        // 2. EventTrig: 0.0 or 1.0
        double eventTrig = emManager.isTriggered(task) ? 1.0 : 0.0;

        // 3. PolicyMatch
        double policyMatch = calculatePolicyMatch(task, metricState, chosenPolicy, eventTrig);

        // 4. 最终加权求和
        return WEIGHT_EIG * eig +
               WEIGHT_URGENCY * urgency +
               WEIGHT_POLICY_MATCH * policyMatch +
               WEIGHT_EVENT_TRIG * eventTrig;
    }

    /**
     * PRIO: 计算任务与当前策略的匹配度。
     */
    private double calculatePolicyMatch(ProbeTask task, MetricState state, Policy policy, double eventTrig) {
        switch (policy) {
            case FOCUS_UNCERTAINTY:
                // 不确定性越高，匹配度越高。归一化（假设最大熵为4.0）
                return Math.min(state.getUncertainty() / 4.0, 1.0);
            case HIGH_FREQ_UNSTABLE:
                // 稳定性越高（波动越大），匹配度越高。归一化（假设最大方差为10.0）
                return Math.min(state.getStability() / 10.0, 1.0);
            case COVERAGE_BALANCER:
                // 对于覆盖策略，所有任务都同等匹配
                return 1.0;
            case EVENT_TRIGGER:
                // 对于事件策略，只有被触发的任务才匹配
                return eventTrig;
            default:
                return 0.0;
        }
    }

    /**
     * 由外部（RFU）调用，用于更新CMAB模型。
     *
     * @param reward         本轮探测获得的奖励
     * @param contextAtTimeOfDecision The context vector that was used to make the decision
     */
    public void updateCmab(double reward, RealVector contextAtTimeOfDecision) {
        if (lastChosenPolicy == null) {
            return;
        }
        log.debug("CMAB更新策略: {}, 奖励: {:.4f}", lastChosenPolicy, reward);
        
        RealMatrix sigma = cmabSigma.get(lastChosenPolicy);
        RealVector mu = cmabMu.get(lastChosenPolicy);
        
        // 贝叶斯线性回归更新公式
        // inv(Sigma_t) = inv(Sigma_{t-1}) + c * c^T / sigma_noise^2
        // mu_t = Sigma_t * (inv(Sigma_{t-1})*mu_{t-1} + c*r / sigma_noise^2)
        
        try {
            DecompositionSolver solver = new LUDecomposition(sigma).getSolver();
            RealMatrix sigmaInv = solver.getInverse();

            RealMatrix newSigmaInv = sigmaInv.add(
                contextAtTimeOfDecision.outerProduct(contextAtTimeOfDecision)
                    .scalarMultiply(1.0 / (THOMPSON_SAMPLING_SIGMA * THOMPSON_SAMPLING_SIGMA))
            );

            DecompositionSolver newSolver = new LUDecomposition(newSigmaInv).getSolver();
            RealMatrix newSigma = newSolver.getInverse();

            RealVector newMu = newSigma.operate(
                sigmaInv.operate(mu).add(
                    contextAtTimeOfDecision.mapMultiply(reward / (THOMPSON_SAMPLING_SIGMA * THOMPSON_SAMPLING_SIGMA))
                )
            );

            cmabSigma.put(lastChosenPolicy, newSigma);
            cmabMu.put(lastChosenPolicy, newMu);

        } catch (Exception e) {
            log.error("CMAB模型更新失败，矩阵计算出错: {}", e.getMessage());
            // 如果计算出错（如矩阵奇异），可以重置该策略的参数
            // initializeCmabForPolicy(lastChosenPolicy);
        }
    }


    // --- 辅助工具 ---
    private static double[][] createIdentityMatrix(int dim) {
        double[][] matrix = new double[dim][dim];
        for (int i = 0; i < dim; i++) {
            matrix[i][i] = 1.0;
        }
        return matrix;
    }

    // 内部辅助类，用于排序
    private static class PrioritizedTask {
        private final ProbeTask task;
        private final double priority;

        PrioritizedTask(ProbeTask task, double priority) {
            this.task = task;
            this.priority = priority;
        }

        ProbeTask getTask() {
            return task;
        }

        double getPriority() {
            return priority;
        }
    }
}
