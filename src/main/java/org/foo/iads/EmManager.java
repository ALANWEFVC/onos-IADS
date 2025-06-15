// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/EmManager.java
// 作用: 实现IADS框架的事件管理器(EM)。
//       负责检测网络中的异常事件，并生成事件触发信号给APS。
// =====================================================================
package org.foo.iads;

import com.google.common.collect.EvictingQueue;
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

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IADS事件管理器(EM)的实现。
 * <p>
 * 该组件扮演网络异常的“哨兵”角色。它持续分析ESM提供的状态数据，
 * 根据预定义的规则检测异常事件，并生成EventTrig信号和num_recent_events
 * 特征，供APS使用。
 */
@Component(immediate = true, service = EmManager.class)
public class EmManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    // --- 事件检测阈值 ---
    // Liveness置信度低于此值被认为是异常
    private static final double LIVENESS_THRESHOLD = 0.5;
    // 定量指标的稳定性高于此值被认为是异常
    private static final double STABILITY_THRESHOLD = 2.0;
    // 用于统计近期事件的时间窗口（秒）
    private static final long RECENT_EVENT_WINDOW_SECONDS = 60;

    // 使用一个有界队列来存储近期事件的时间戳
    @SuppressWarnings("UnstableApiUsage")
    private final EvictingQueue<Instant> recentEvents = EvictingQueue.create(1000);

    // 引用ESM来获取状态数据
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EsmManager esmManager;

    private Set<ProbeTask> triggeredTasks = new HashSet<>();

    @Activate
    protected void activate() {
        log.info("IADS EM - 事件管理器已启动。");
    }

    @Deactivate
    protected void deactivate() {
        recentEvents.clear();
        log.info("IADS EM - 事件管理器已停止。");
    }

    /**
     * 执行一轮事件检测。
     * 该方法应该被主控组件周期性调用。
     */
    public void runEventDetection() {
        Set<ProbeTask> currentTriggeredTasks = new HashSet<>();

        // 遍历所有链路的所有指标，检测异常
        esmManager.getTrackedLinks().forEach(link -> {
            // 1. 检测Liveness异常
            LivenessState livenessState = (LivenessState) esmManager.getOrCreateLinkState(link)
                    .getMetricState(Metric.LIVENESS);
            if (livenessState.getProbabilityUp() < LIVENESS_THRESHOLD) {
                ProbeTask task = new ProbeTask(link, Metric.LIVENESS);
                if (!triggeredTasks.contains(task)) {
                    // 这是一个新事件
                    log.debug("事件检测: 链路 {} 的 Liveness 异常 (P(UP)={:.3f})",
                             link, livenessState.getProbabilityUp());
                    recentEvents.add(Instant.now());
                }
                currentTriggeredTasks.add(task);
            }

            // 2. 检测定量指标异常 (RTT, PLR)
            for (Metric metric : new Metric[]{Metric.RTT, Metric.PLR}) {
                QuantitativeState qState = (QuantitativeState) esmManager.getOrCreateLinkState(link)
                        .getMetricState(metric);
                if (qState != null && qState.getStability() > STABILITY_THRESHOLD) {
                    ProbeTask task = new ProbeTask(link, metric);
                    if (!triggeredTasks.contains(task)) {
                        // 这是一个新事件
                        log.debug("事件检测: 任务 {} 异常 (S={:.3f})", task, qState.getStability());
                        recentEvents.add(Instant.now());
                    }
                    currentTriggeredTasks.add(task);
                }
            }
        });

        // 更新本轮触发的任务列表
        this.triggeredTasks = currentTriggeredTasks;
    }

    /**
     * 检查指定的任务是否被事件触发。
     *
     * @param task 目标任务
     * @return 如果被触发则返回true，否则返回false
     */
    public boolean isTriggered(ProbeTask task) {
        return triggeredTasks.contains(task);
    }

    /**
     * 获取近期事件的数量。
     * “近期”由 RECENT_EVENT_WINDOW_SECONDS 定义。
     *
     * @return 近期事件总数
     */
    public int getNumRecentEvents() {
        Instant threshold = Instant.now().minusSeconds(RECENT_EVENT_WINDOW_SECONDS);
        // EvictingQueue不是线程安全的，但在单线程调度器中调用是OK的。
        // 为保险起见，可以创建一个副本进行操作。
        return (int) recentEvents.stream()
                .filter(t -> t.isAfter(threshold))
                .count();
    }
}