// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/IadsAppComponent.java
// 作用: IADS应用的主控组件，是整个应用的入口和总指挥。
//       负责初始化所有模块、监听网络拓扑、并驱动核心调度循环。
// =====================================================================
package org.foo.iads;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.foo.iads.model.ProbeTask;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Link;
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.link.LinkListener;
import org.onosproject.net.link.LinkService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.ComponentContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.foo.iads.IadsConfig.get;
import static org.foo.iads.IadsConfig.PROBE_INTERVAL_SECONDS;
import static org.foo.iads.IadsConfig.TASKS_PER_CYCLE;
import static org.foo.iads.IadsConfig.INITIAL_DELAY_SECONDS;

/**
 * IADS应用的主组件。
 */
@Component(immediate = true,
           property = {
               PROBE_INTERVAL_SECONDS + ":Integer=5",
               TASKS_PER_CYCLE + ":Integer=10",
               INITIAL_DELAY_SECONDS + ":Integer=10"
           })
public class IadsAppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final LinkListener linkListener = new InternalLinkListener();

    // --- ONOS 服务引用 ---
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected LinkService linkService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    // --- IADS 内部服务引用 ---
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EsmManager esmManager;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ApsManager apsManager;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EmManager emManager;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PeAndRfuManager peAndRfuManager;

    private ApplicationId appId;
    private ScheduledExecutorService scheduler;

    /** IADS调度周期间隔 (秒). */
    private int probeIntervalSeconds = 5;

    /** 每个周期调度的任务数 (K). */
    private int tasksPerCycle = 10;
    
    /** 初始延迟启动时间 (秒). */
    private int initialDelaySeconds = 10;

    @Activate
    protected void activate(ComponentContext context) {
        appId = coreService.registerApplication("org.foo.iads");
        cfgService.registerProperties(getClass());
        modified(context);

        linkService.addListener(linkListener);

        // 初始化时，将当前所有存在的链路加入ESM进行跟踪
        linkService.getActiveLinks().forEach(link -> {
            log.info("发现初始链路: {}", link);
            esmManager.getOrCreateLinkState(link);
        });

        // 初始化并启动调度器
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::runIadsCycle,
                                      initialDelaySeconds,
                                      probeIntervalSeconds,
                                      TimeUnit.SECONDS);

        log.info("IADS应用已启动，应用ID: {}. 调度周期: {}s, K={}",
                 appId.id(), probeIntervalSeconds, tasksPerCycle);
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        linkService.removeListener(linkListener);
        if (scheduler != null) {
            scheduler.shutdown();
        }
        log.info("IADS应用已停止。");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();
        if (properties == null) {
            return;
        }

        probeIntervalSeconds = get(properties, PROBE_INTERVAL_SECONDS);
        tasksPerCycle = get(properties, TASKS_PER_CYCLE);
        initialDelaySeconds = get(properties, INITIAL_DELAY_SECONDS);
        log.info("IADS配置已更新: 调度周期={}s, K={}", probeIntervalSeconds, tasksPerCycle);

        // 如果调度器正在运行，可以重新安排任务
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(this::runIadsCycle,
                                      initialDelaySeconds,
                                      probeIntervalSeconds,
                                      TimeUnit.SECONDS);
            log.info("IADS调度器已根据新配置重启。");
        }
    }


    /**
     * IADS的核心调度循环。
     */
    private void runIadsCycle() {
        try {
            log.info("--- IADS新一轮调度开始 ---");
            
            // 1. EM: 运行事件检测
            emManager.runEventDetection();
            
            // 2. APS: 调度Top-K任务
            //    在调度前，记录下当前的上下文向量，用于后续CMAB模型的更新
            RealVector contextForCmab = new ArrayRealVector(
                    esmManager.calculateContextVector(emManager.getNumRecentEvents())
            );
            List<ProbeTask> topKTasks = apsManager.scheduleTopKTasks(tasksPerCycle);

            if (topKTasks.isEmpty()) {
                log.info("没有需要执行的任务，本轮调度结束。");
                return;
            }

            // 3. PE/RFU: 执行任务
            peAndRfuManager.executeTasks(topKTasks, contextForCmab);

        } catch (Exception e) {
            log.error("IADS调度循环发生异常", e);
        }
    }

    /**
     * 内部LinkListener，用于动态感知网络拓扑变化。
     */
    private class InternalLinkListener implements LinkListener {
        @Override
        public void event(LinkEvent event) {
            Link link = event.subject();
            switch (event.type()) {
                case LINK_ADDED:
                    log.info("链路新增: {}", link);
                    esmManager.getOrCreateLinkState(link);
                    break;
                case LINK_REMOVED:
                    log.info("链路移除: {}", link);
                    esmManager.removeLinkState(link);
                    break;
                case LINK_UPDATED:
                    // 例如链路状态变为INACTIVE，可以视为一种移除
                    if (!link.state().equals(Link.State.ACTIVE)) {
                        log.info("链路状态更新为非活跃: {}", link);
                        esmManager.removeLinkState(link);
                    }
                    break;
                default:
                    break;
            }
        }
    }
}