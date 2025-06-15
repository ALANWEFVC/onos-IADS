// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/IadsConfig.java
// 作用: 应用的配置参数管理类。
// =====================================================================
package org.foo.iads;

import org.onlab.util.Tools;
import org.osgi.service.component.ComponentContext;

import java.util.Dictionary;

/**
 * IADS应用的配置属性管理。
 */
public final class IadsConfig {

    private IadsConfig() {
        // 禁止实例化
    }

    public static final String PROBE_INTERVAL_SECONDS = "probeIntervalSeconds";
    public static final String TASKS_PER_CYCLE = "tasksPerCycle";
    public static final String INITIAL_DELAY_SECONDS = "initialDelaySeconds";

    /**
     * 从配置属性中提取整型值。
     *
     * @param properties 属性集
     * @param name       属性名
     * @return 整数值
     */
    public static int get(Dictionary<?, ?> properties, String name) {
        Object value = properties.get(name);
        return Tools.getIntegerProperty(properties, name);
    }
}

