// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/EsmManager.java
// 作用: 实现IADS框架的实体状态管理器(ESM)。
//       负责维护网络链路状态，并为其他组件提供状态查询、更新和上下文生成功能。
// =====================================================================
package org.foo.iads;

import org.foo.iads.model.LinkState;
import org.foo.iads.model.LivenessState;
import org.foo.iads.model.Metric;
import org.foo.iads.model.MetricState;
import org.foo.iads.model.QuantitativeState;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Link;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.Versioned;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IADS实体状态管理器(ESM)的实现。
 * <p>
 * 该组件作为网络状态的单一事实来源，使用ONOS的分布式存储来维护每条链路
 * 的Liveness, RTT, PLR等指标的状态。它还负责计算APS决策所需的上下文向量。
 */
@Component(immediate = true, service = EsmManager.class)
public class EsmManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final String IADS_APP_ID = "org.foo.iads";
    private static final String LINK_STATE_MAP_NAME = "iads-link-states";

    // 测量RTT和PLR时的默认测量噪声方差
    private static final double DEFAULT_MEASUREMENT_NOISE = 1.0;

    // --- ONOS 服务引用 ---
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected StorageService storageService;

    /**
     * 分布式链路状态存储。
     * Key: ONOS的Link对象。
     * Value: 我们自定义的LinkState对象，包含了该链路所有指标的状态。
     */
    private ConsistentMap<Link, LinkState> linkStates;

    private ApplicationId appId;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(IADS_APP_ID);

        // 初始化ConsistentMap，并为其提供Kryo序列化器
        // 序列化器对于在分布式环境中正确存储自定义对象至关重要
        linkStates = storageService.<Link, LinkState>consistentMapBuilder()
                .withName(LINK_STATE_MAP_NAME)
                .withSerializer(Serializer.using(
                        Arrays.asList(KryoNamespaces.API, KryoNamespaces.BASIC),
                        // 注册所有自定义的可序列化模型
                        LinkState.class,
                        Metric.class,
                        MetricState.class,
                        LivenessState.class,
                        QuantitativeState.class
                ))
                .build();

        log.info("IADS ESM - 实体状态管理器已启动。应用ID: {}", appId.id());
    }

    @Deactivate
    protected void deactivate() {
        // 应用停用时，可以选择性地清理Map，但这通常是不必要的，
        // 因为状态是持久化的。如果需要，可以取消下面的注释。
        // linkStates.clear();
        log.info("IADS ESM - 实体状态管理器已停止。");
    }

    /**
     * 获取或创建一个链路的状态对象。
     * 如果该链路的状态首次被请求，则会创建一个新的、具有初始默认值的LinkState对象
     * 并存入ConsistentMap中。
     *
     * @param link 目标链路
     * @return 该链路的LinkState对象
     */
    public LinkState getOrCreateLinkState(Link link) {
        // computeIfAbsent确保了操作的原子性，在集群环境中是安全的
        Versioned<LinkState> versioned = linkStates.computeIfAbsent(link, k -> new LinkState());
        return versioned.value();
    }

    /**
     * 获取当前ESM正在跟踪的所有链路。
     *
     * @return 一个包含所有被跟踪链路的Set
     */
    public Set<Link> getTrackedLinks() {
        return linkStates.keySet();
    }

    /**
     * 根据探测结果更新链路的Liveness状态。
     *
     * @param link 探测的链路
     * @param isUp 探测结果是否为UP
     */
    public void updateLivenessState(Link link, boolean isUp) {
        linkStates.compute(link, (l, state) -> {
            if (state == null) {
                state = new LinkState();
            }
            LivenessState livenessState = (LivenessState) state.getMetricState(Metric.LIVENESS);
            livenessState.update(isUp);
            return state;
        });
    }

    /**
     * 根据探测结果更新链路的定量指标（RTT或PLR）状态。
     *
     * @param link   探测的链路
     * @param metric 指标类型（必须是RTT或PLR）
     * @param value  测量值
     */
    public void updateQuantitativeState(Link link, Metric metric, double value) {
        if (metric == Metric.LIVENESS) {
            log.warn("updateQuantitativeState不能用于Liveness指标，请使用updateLivenessState方法。");
            return;
        }
        linkStates.compute(link, (l, state) -> {
            if (state == null) {
                state = new LinkState();
            }
            QuantitativeState quantitativeState = (QuantitativeState) state.getMetricState(metric);
            // 这里的噪声可以根据不同指标进行配置，暂时使用默认值
            quantitativeState.update(value, DEFAULT_MEASUREMENT_NOISE);
            return state;
        });
    }

    /**
     * 为指定的任务（链路+指标）设置探测间隔。
     * 这个方法主要由CTLC模块调用。
     *
     * @param link     目标链路
     * @param metric   目标指标
     * @param interval 新的探测间隔（秒）
     */
    public void setProbeInterval(Link link, Metric metric, double interval) {
        linkStates.computeIfPresent(link, (l, state) -> {
            MetricState metricState = state.getMetricState(metric);
            if (metricState != null) {
                metricState.setProbeInterval(interval);
            }
            return state;
        });
    }

    /**
     * 计算供APS/CMAB使用的上下文向量c。
     * c = [avg_uncertainty, num_recent_events, unstable_entity_ratio, avg_stable_urgency]
     *
     * @param numRecentEvents 来自EM模块的近期事件数
     * @return 一个包含4个上下文特征的double数组
     */
    public double[] calculateContextVector(int numRecentEvents) {
        // 获取所有状态值的快照以进行计算，避免在迭代时被修改
        Map<Link, LinkState> allStates = linkStates.asJavaMap();
        if (allStates.isEmpty()) {
            return new double[]{0.0, 0.0, 0.0, 0.0};
        }

        double totalUncertainty = 0;
        int unstableEntityCount = 0;
        double totalStableUrgency = 0;
        int stableMetricCount = 0;
        int totalMetricCount = 0;

        // 定义不稳定的阈值
        final double stabilityThreshold = 2.0; // S > 2.0 被认为不稳定

        for (LinkState state : allStates.values()) {
            boolean isEntityUnstable = false;
            for (Metric metric : Metric.values()) {
                MetricState metricState = state.getMetricState(metric);
                if (metricState == null) continue;

                totalUncertainty += metricState.getUncertainty();
                totalMetricCount++;

                double stability = metricState.getStability();
                if (stability > stabilityThreshold) {
                    isEntityUnstable = true;
                } else {
                    // 计算稳定实体的Urgency
                    double urgency = (System.currentTimeMillis() - metricState.getLastUpdateTime()) /
                            (metricState.getProbeInterval() * 1000.0);
                    totalStableUrgency += urgency;
                    stableMetricCount++;
                }
            }
            if (isEntityUnstable) {
                unstableEntityCount++;
            }
        }

        // 1. 平均不确定性 (avg_uncertainty)
        double avgUncertainty = totalMetricCount > 0 ? totalUncertainty / totalMetricCount : 0.0;

        // 2. 近期事件数 (num_recent_events) - 归一化 (假设最大值为600)
        double normalizedNumRecentEvents = Math.min(numRecentEvents / 600.0, 1.0);

        // 3. 不稳定实体比例 (unstable_entity_ratio)
        double unstableEntityRatio = allStates.size() > 0 ? (double) unstableEntityCount / allStates.size() : 0.0;

        // 4. 稳定实体的平均紧急度 (avg_stable_urgency)
        double avgStableUrgency = stableMetricCount > 0 ? totalStableUrgency / stableMetricCount : 0.0;

        // IADS PDF中的示例值 c=[0.633, 0.2, 0.3, 1.2]，我们的计算结果数量级应类似
        double[] context = new double[]{avgUncertainty, normalizedNumRecentEvents, unstableEntityRatio, avgStableUrgency};
        log.debug("计算出的上下文向量: [avg_H={:.3f}, events_norm={:.3f}, unstable_ratio={:.3f}, avg_urgency={:.3f}]",
                  context[0], context[1], context[2], context[3]);

        return context;
    }

    /**
     * 当一条链路从网络中移除时，从状态表中也移除它。
     * @param link 被移除的链路
     */
    public void removeLinkState(Link link) {
        linkStates.remove(link);
        log.info("链路 {} 已从ESM中移除", link);
    }
}
