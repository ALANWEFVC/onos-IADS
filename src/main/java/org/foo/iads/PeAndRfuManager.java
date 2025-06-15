// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/PeAndRfuManager.java
// 作用: 实现IADS的探测执行器(PE)和结果融合单元(RFU)。
//       负责执行探测任务、处理结果、更新状态并计算奖励以完成学习闭环。
// =====================================================================
package org.foo.iads;

import com.google.common.collect.Maps;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.foo.iads.model.Metric;
import org.foo.iads.model.ProbeTask;
import org.onlab.packet.ChassisId;
import org.onlab.packet.Ethernet;
import org.onlab.packet.LLDP;
import org.onlab.packet.LLDPTLV;
import org.onlab.packet.LLDPOrganizationalTLV;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.Link;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;


@Component(immediate = true, service = PeAndRfuManager.class)
public class PeAndRfuManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    // --- 自定义LLDP TLV的常量 ---
    // 使用一个在IANA注册的OUI，或者一个私有的OUI
    private static final byte[] IADS_OUI = {(byte) 0x08, (byte) 0x00, (byte) 0x27};
    // IADS自定义的子类型，用于标识我们的探测包
    private static final byte IADS_SUBTYPE = 0x01;


    // --- ONOS 服务引用 ---
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected LinkService linkService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EsmManager esmManager;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ApsManager apsManager;

    private ApplicationId appId;
    private IadsPacketProcessor packetProcessor;

    // --- RFU 状态变量 ---
    // 存储当前正在执行的Top-K任务批次信息
    private volatile BatchProbeContext currentBatchContext;


    @Activate
    protected void activate() {
        appId = coreService.registerApplication("org.foo.iads");

        // 注册PacketProcessor来捕获返回的探测包
        packetProcessor = new IadsPacketProcessor();
        packetService.addProcessor(packetProcessor, PacketProcessor.director(2));

        // 请求捕获LLDP类型的数据包
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_LLDP);
        packetService.requestPackets(selector.build(), PacketPriority.CONTROL, appId);

        log.info("IADS PE/RFU - 探测执行与结果融合单元已启动。");
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(packetProcessor);
        packetService.cancelPackets(DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_LLDP).build(), PacketPriority.CONTROL, appId);
        log.info("IADS PE/RFU - 探测执行与结果融合单元已停止。");
    }

    /**
     * PE: 执行一批由APS调度出的探测任务。
     *
     * @param tasks          要执行的Top-K任务列表
     * @param contextForCmab 执行此批任务决策时所使用的上下文向量
     */
    public void executeTasks(List<ProbeTask> tasks, RealVector contextForCmab) {
        if (tasks.isEmpty()) {
            return;
        }
        log.info("PE开始执行 {} 个探测任务...", tasks.size());

        // 创建新的批次上下文
        this.currentBatchContext = new BatchProbeContext(tasks, contextForCmab);

        for (ProbeTask task : tasks) {
            switch (task.getMetric()) {
                case LIVENESS:
                case RTT:
                    // Liveness 和 RTT 都通过发送同一个LLDP包来完成
                    sendLldpProbe(task);
                    break;
                case PLR:
                    // PLR 通过查询端口统计数据来完成
                    // 此处简化处理：假设PLR探测是即时完成的。在实际高级实现中，
                    // 它需要比较前后两次的统计数据。为简化，我们暂时不实现PLR。
                    log.warn("PLR探测功能暂未实现，跳过任务: {}", task);
                    currentBatchContext.markTaskCompleted(task, 0); // 假设无不确定性减少
                    break;
                default:
                    log.warn("不支持的探测任务类型: {}", task.getMetric());
                    currentBatchContext.markTaskCompleted(task, 0);
                    break;
            }
        }
    }


    /**
     * PE: 为Liveness/RTT任务发送一个带有自定义时间戳的LLDP探测包。
     */
    private void sendLldpProbe(ProbeTask task) {
        Link link = task.getLink();
        Device srcDevice = deviceService.getDevice(link.src().deviceId());
        if (srcDevice == null) {
            log.error("找不到源设备 {}", link.src().deviceId());
            currentBatchContext.markTaskCompleted(task, 0);
            return;
        }
        
        // 检查设备是否可用和设备类型
        if (srcDevice.type() != Device.Type.SWITCH || !deviceService.isAvailable(srcDevice.id())) {
            log.warn("源设备 {} 不可用或不是交换机，跳过探测任务", link.src().deviceId());
            currentBatchContext.markTaskCompleted(task, 0);
            return;
        }
        
        // 检查端口是否存在
        if (deviceService.getPort(link.src()) == null) {
            log.warn("源端口 {} 不存在，跳过探测任务", link.src());
            currentBatchContext.markTaskCompleted(task, 0);
            return;
        }

        // 创建LLDP包
        LLDP lldp = new LLDP();
        
        // Set chassis ID
        LLDPTLV chassisTlv = new LLDPTLV();
        chassisTlv.setType(LLDP.CHASSIS_TLV_TYPE);
        String chassisIdStr = srcDevice.chassisId().toString();
        byte[] chassisBytes = chassisIdStr.getBytes();
        // Add subtype byte for chassis ID
        byte[] chassisWithSubtype = new byte[chassisBytes.length + 1];
        chassisWithSubtype[0] = 7; // Chassis ID subtype: Network Address
        System.arraycopy(chassisBytes, 0, chassisWithSubtype, 1, chassisBytes.length);
        chassisTlv.setLength((short) chassisWithSubtype.length);
        chassisTlv.setValue(chassisWithSubtype);
        lldp.setChassisId(chassisTlv);
        
        // Set port ID
        LLDPTLV portTlv = new LLDPTLV();
        portTlv.setType(LLDP.PORT_TLV_TYPE);
        byte[] portBytes = ByteBuffer.allocate(1 + link.src().port().toString().length())
                                   .put((byte) 7) // Subtype: Interface Name
                                   .put(link.src().port().toString().getBytes())
                                   .array();
        portTlv.setLength((short) portBytes.length);
        portTlv.setValue(portBytes);
        lldp.setPortId(portTlv);
        
        // Set TTL
        LLDPTLV ttlTlv = new LLDPTLV();
        ttlTlv.setType(LLDP.TTL_TLV_TYPE);
        ttlTlv.setLength((short) 2);
        // TTL in network byte order (big-endian)
        ByteBuffer ttlBuffer = ByteBuffer.allocate(2);
        ttlBuffer.putShort((short) 120); // 120 seconds
        ttlTlv.setValue(ttlBuffer.array());
        lldp.setTtl(ttlTlv);

        // --- 创建自定义的组织特定TLV来携带时间戳 ---
        long sendTime = System.nanoTime(); // 使用纳秒级时间戳
        currentBatchContext.recordProbeSent(task, sendTime);

        // 手动构造组织特定TLV以确保正确的长度
        LLDPTLV iadsTlv = new LLDPTLV();
        iadsTlv.setType(LLDPOrganizationalTLV.ORGANIZATIONAL_TLV_TYPE); // Type = 127
        
        // 构造TLV值: OUI (3字节) + SubType (1字节) + InfoString (8字节)
        ByteBuffer tlvValue = ByteBuffer.allocate(3 + 1 + 8); // 总共12字节
        tlvValue.put(IADS_OUI);           // 3字节 OUI
        tlvValue.put(IADS_SUBTYPE);       // 1字节 SubType  
        tlvValue.putLong(sendTime);       // 8字节时间戳
        
        iadsTlv.setLength((short) tlvValue.capacity());
        iadsTlv.setValue(tlvValue.array());
        
        lldp.addOptionalTLV(iadsTlv);
        // --- 自定义TLV结束 ---

        // 创建以太网帧
        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(MacAddress.ONOS_LLDP);
        // Use default source MAC if port MAC is not available
        String portMac = deviceService.getPort(link.src()).annotations().value("portMac");
        if (portMac != null) {
            eth.setSourceMACAddress(MacAddress.valueOf(portMac));
        } else {
            eth.setSourceMACAddress(MacAddress.ONOS_LLDP);
        }
        eth.setEtherType(Ethernet.TYPE_LLDP);
        eth.setPayload(lldp);

        // 通过PacketService发送 - 需要指定正确的TrafficTreatment来指定出端口
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(link.src().port())
                .build();
        
        try {
            byte[] ethBytes = eth.serialize();
            if (ethBytes != null && ethBytes.length > 0) {
                packetService.emit(new DefaultOutboundPacket(link.src().deviceId(),
                                                           treatment,
                                                           ByteBuffer.wrap(ethBytes)));
                log.trace("为任务 {} 发送了LLDP探测包", task);
            } else {
                log.error("LLDP包序列化失败，跳过任务: {}", task);
                currentBatchContext.markTaskCompleted(task, 0);
            }
        } catch (Exception e) {
            log.error("发送LLDP探测包时发生异常: {}, 任务: {}", e.getMessage(), task, e);
            currentBatchContext.markTaskCompleted(task, 0);
        }
    }


    /**
     * RFU: 内部类，实现PacketProcessor来处理返回的探测包。
     */
    private class IadsPacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            // 如果已经被处理过，则直接返回
            if (context.isHandled()) {
                return;
            }

            Ethernet ethPkt = context.inPacket().parsed();
            if (ethPkt == null || ethPkt.getEtherType() != Ethernet.TYPE_LLDP) {
                return; // 不是LLDP包，忽略
            }

            LLDP lldp = (LLDP) ethPkt.getPayload();
            LLDPTLV iadsTlv = findIadsTlv(lldp);
            if (iadsTlv == null) {
                return; // 不是我们的探测包，忽略
            }
            long receiveTime = System.nanoTime();

            // 解析TLV获取发送时间
            byte[] tlvValueBytes = iadsTlv.getValue();
            // 跳过OUI(3字节) + SubType(1字节)，直接读取时间戳(8字节)
            if (tlvValueBytes == null || tlvValueBytes.length < 12) {
                log.trace("IADS TLV数据不完整，忽略此包");
                return;
            }
            
            long sendTime;
            try {
                ByteBuffer tlvValue = ByteBuffer.wrap(tlvValueBytes, 4, 8); // 从第4字节开始读8字节
                sendTime = tlvValue.getLong();
            } catch (Exception e) {
                log.trace("TLV时间戳解析失败: {}", e.getMessage());
                return;
            }

            // 根据包的源信息和LLDP内容，重建探测任务
            ConnectPoint inCp = context.inPacket().receivedFrom();
            LLDPTLV chassisTlv = lldp.getChassisId();
            if (chassisTlv == null) {
                log.trace("LLDP包缺少Chassis ID，忽略");
                return;
            }
            
            byte[] chassisBytes = chassisTlv.getValue();
            if (chassisBytes == null || chassisBytes.length < 2) {
                log.trace("Chassis ID数据无效，忽略");
                return;
            }
            
            // Skip the subtype byte and get chassis ID
            String chassisIdStr = new String(chassisBytes, 1, chassisBytes.length - 1);
            ChassisId chassisId = new ChassisId(chassisIdStr);

            // 根据LLDP包信息重建链路 - 从源到接收端口
            try {
                // Parse port info from LLDP port ID TLV
                LLDPTLV portTlv = lldp.getPortId();
                if (portTlv == null) {
                    log.trace("LLDP包缺少Port ID，忽略");
                    return;
                }
                
                byte[] portBytes = portTlv.getValue();
                if (portBytes == null || portBytes.length < 2) {
                    log.trace("Port ID数据无效，忽略");
                    return;
                }
                
                // Skip the subtype byte and get port name
                String portName = new String(portBytes, 1, portBytes.length - 1);
                
                // 查找对应的设备
                for (Device device : deviceService.getDevices()) {
                    if (new ChassisId(device.chassisId().value()).equals(chassisId)) {
                        ConnectPoint srcCp = new ConnectPoint(device.id(), PortNumber.portNumber(portName));
                        ConnectPoint dstCp = inCp; // 接收端口作为目标
                        
                        // 使用LinkService查找真实的链路
                        Link realLink = null;
                        for (Link link : linkService.getActiveLinks()) {
                            if (link.src().equals(srcCp) && link.dst().equals(dstCp)) {
                                realLink = link;
                                break;
                            }
                        }
                        
                        if (realLink != null) {
                            ProbeTask task = new ProbeTask(realLink, Metric.RTT);
                            processProbeResult(task, sendTime, receiveTime);
                            context.block(); // 阻止其他应用处理这个包
                        } else {
                            log.debug("未找到对应链路: {} -> {}", srcCp, dstCp);
                        }
                        break;
                    }
                }
            } catch (NumberFormatException e) {
                log.trace("端口号解析失败，可能不是标准LLDP包: {}", e.getMessage());
            } catch (IllegalArgumentException e) {
                log.trace("LLDP包格式不符合预期: {}", e.getMessage());
            } catch (Exception e) {
                log.trace("解析LLDP包时发生异常（非IADS探测包）: {}", 
                         e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }

        /**
         * 在LLDP包中查找我们自定义的TLV。
         */
        private LLDPTLV findIadsTlv(LLDP lldp) {
            for (LLDPTLV tlv : lldp.getOptionalTLVList()) {
                if (tlv.getType() == LLDPOrganizationalTLV.ORGANIZATIONAL_TLV_TYPE) {
                    byte[] value = tlv.getValue();
                    if (value.length >= 4) {
                        // 检查OUI (前3字节)
                        boolean ouiMatches = true;
                        for (int i = 0; i < 3; i++) {
                            if (value[i] != IADS_OUI[i]) {
                                ouiMatches = false;
                                break;
                            }
                        }
                        // 检查SubType (第4字节)
                        if (ouiMatches && value[3] == IADS_SUBTYPE) {
                            return tlv;
                        }
                    }
                }
            }
            return null;
        }

        /**
         * RFU: 处理探测结果，更新状态并检查批次是否完成。
         */
        private void processProbeResult(ProbeTask task, long sendTime, long receiveTime) {
            if (currentBatchContext == null) {
                return;
            }
            
            // 检查任务是否有效
            if (task == null || task.getLink() == null) {
                log.warn("收到无效的探测任务，忽略");
                return;
            }
            
            // 从批次上下文中获取发送时间，以获得更准确的RTT
            long originalSendTime = currentBatchContext.getSentTime(task);
            if(originalSendTime == 0L){
                log.warn("收到了一个未记录发送时间的探测回复: {}", task);
                return; // 忽略这个无法匹配的回复
            }

            // 1. 更新Liveness状态
            esmManager.updateLivenessState(task.getLink(), true);

            // 2. 更新RTT状态
            // RTT (ms) = (receiveTime - sendTime) / 1,000,000
            double rttMillis = (double) (receiveTime - originalSendTime) / 1_000_000.0;
            if (rttMillis < 0) { // 可能是时钟回绕或错误
                rttMillis = 0;
            }
            esmManager.updateQuantitativeState(task.getLink(), Metric.RTT, rttMillis);
            log.trace("探测结果: {} RTT={:.3f}ms", task.getLink(), rttMillis);

            // 3. 标记任务完成，并传递不确定性减少量
            double uncertaintyReduction = currentBatchContext.getUncertaintyBefore(task) -
                                          esmManager.getOrCreateLinkState(task.getLink())
                                                  .getMetricState(task.getMetric()).getUncertainty();
            
            // Liveness的更新也可能减少不确定性，这里简化处理，只考虑RTT任务本身。
            double livenessUncertaintyReduction = currentBatchContext.getUncertaintyBefore(new ProbeTask(task.getLink(), Metric.LIVENESS)) -
                                          esmManager.getOrCreateLinkState(task.getLink())
                                                  .getMetricState(Metric.LIVENESS).getUncertainty();

            currentBatchContext.markTaskCompleted(task, uncertaintyReduction);
            currentBatchContext.markTaskCompleted(new ProbeTask(task.getLink(), Metric.LIVENESS), livenessUncertaintyReduction);
        }
    }


    /**
     * RFU: 用于管理一个Top-K探测批次的内部类。
     */
    private class BatchProbeContext {
        private final RealVector contextForCmab;
        private final ConcurrentMap<ProbeTask, Double> uncertaintyBeforeMap = Maps.newConcurrentMap();
        private final AtomicInteger completionCounter;
        private final AtomicInteger totalTasks;
        private final ConcurrentMap<ProbeTask, Long> probesSentTimes = Maps.newConcurrentMap();

        BatchProbeContext(List<ProbeTask> tasks, RealVector contextForCmab) {
            this.contextForCmab = contextForCmab;
            tasks.forEach(task -> {
                // TODO: Fix MetricState type issue
                // For now, use default uncertainty value
                uncertaintyBeforeMap.put(task, 2.0); // Default uncertainty
            });
            this.completionCounter = new AtomicInteger(0);
            this.totalTasks = new AtomicInteger(uncertaintyBeforeMap.size());
        }
        
        void recordProbeSent(ProbeTask task, long sendTime) {
            probesSentTimes.put(task, sendTime);
        }
        
        long getSentTime(ProbeTask task) {
            return probesSentTimes.getOrDefault(task, 0L);
        }

        double getUncertaintyBefore(ProbeTask task) {
            return uncertaintyBeforeMap.getOrDefault(task, 0.0);
        }
        
        void markTaskCompleted(ProbeTask task, double uncertaintyReduction) {
            // 从map中移除，防止重复计算
            Double u = uncertaintyBeforeMap.remove(task);

            if (u != null) { // 确保只处理一次
                int completed = completionCounter.incrementAndGet();
                log.trace("批次任务完成 {}/{}", completed, totalTasks.get());

                if (completed >= totalTasks.get()) {
                    // 所有任务完成，计算总奖励并更新CMAB
                    finishBatch();
                }
            }
        }
        
        private void finishBatch() {
             // 奖励公式: r_t = 0.7 * AUncertainty_normalized - 0.3 * K_normalized
            double totalUncertaintyReduction = uncertaintyBeforeMap.values().stream().mapToDouble(d -> d).sum();
            
            // 归一化
            // 每个任务最大熵减少约3.5，K个任务的最大减少就是 3.5 * K
            double maxPossibleReduction = totalTasks.get() * 3.5;
            double normalizedReduction = maxPossibleReduction > 0 ? totalUncertaintyReduction / maxPossibleReduction : 0;
            
            // 探测成本归一化（假设最大并行探测数K_max=50）
            double kNormalized = totalTasks.get() / 50.0;
            
            double reward = 0.7 * normalizedReduction - 0.3 * kNormalized;

            log.info("RFU: 探测批次完成。总不确定性减少: {}, 计算奖励: {}",
                     String.format("%.4f", totalUncertaintyReduction), String.format("%.4f", reward));
            
            // 更新CMAB模型
            apsManager.updateCmab(reward, contextForCmab);
            
            // 清理上下文，准备下一批
            PeAndRfuManager.this.currentBatchContext = null;
        }
    }
}