// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/model/ProbeTask.java
// 作用: 定义了一个探测任务，是APS调度的基本单元。
// =====================================================================
package org.foo.iads.model;

import org.onosproject.net.Link;
import java.util.Objects;

/**
 * 代表一个具体的探测任务。
 * 每个任务都针对一个特定的网络实体（链路）和一个特定的指标。
 */
public class ProbeTask {

    private final Link link;
    private final Metric metric;

    /**
     * 构造一个探测任务。
     *
     * @param link   目标链路
     * @param metric 要探测的指标
     */
    public ProbeTask(Link link, Metric metric) {
        this.link = link;
        this.metric = metric;
    }

    public Link getLink() {
        return link;
    }

    public Metric getMetric() {
        return metric;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProbeTask probeTask = (ProbeTask) o;
        return Objects.equals(link, probeTask.link) &&
                metric == probeTask.metric;
    }

    @Override
    public int hashCode() {
        return Objects.hash(link, metric);
    }

    @Override
    public String toString() {
        if (link == null) {
            return "ProbeTask{link=null, metric=" + metric + '}';
        }
        return "ProbeTask{" +
                "link=" + link.src().deviceId() + "/" + link.src().port() +
                " -> " + link.dst().deviceId() + "/" + link.dst().port() +
                ", metric=" + metric +
                '}';
    }
}