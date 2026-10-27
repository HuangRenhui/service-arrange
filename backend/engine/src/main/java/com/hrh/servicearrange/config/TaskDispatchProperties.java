package com.hrh.servicearrange.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 任务投递模式配置：MQ / 本地直连 / 自动降级。
 * <p>
 * <ul>
 *     <li>{@code mq}：只走 RabbitMQ；</li>
 *     <li>{@code local}：只走进程内直连，**不创建任何 MQ 连接**，可脱离 RabbitMQ 独立运行；</li>
 *     <li>{@code auto}：优先 MQ；MQ 不可达或投递失败时降级本地直连，MQ 恢复后自动切回。</li>
 * </ul>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "task.dispatch")
public class TaskDispatchProperties {

    public static final String MODE_MQ = "mq";
    public static final String MODE_LOCAL = "local";
    public static final String MODE_AUTO = "auto";

    /**
     * 投递模式：mq / local / auto，缺省 auto
     */
    private String mode = MODE_AUTO;

    /**
     * MQ 不可用或投递失败时是否降级为本地直连；false 时直接抛异常（用于严格依赖 MQ 的部署）
     */
    private boolean degradeToLocal = true;

    /**
     * MQ 可达性探活间隔（毫秒）
     */
    private long probeIntervalMs = 10000L;

    /**
     * 探活连接超时（毫秒）
     */
    private int probeTimeoutMs = 1000;

    /**
     * 探活地址 host:port；为空时从 binder / spring.rabbitmq 配置推导
     */
    private String address;

    /**
     * 本地直连分片线程数；0 表示按 CPU 自动（cpu*2，最小 4）
     */
    private int localThreads = 0;

    public String normalizedMode() {
        if (mode == null) {
            return MODE_AUTO;
        }
        String m = mode.trim().toLowerCase();
        if (MODE_MQ.equals(m) || MODE_LOCAL.equals(m)) {
            return m;
        }
        return MODE_AUTO;
    }

    public boolean isMqMode() {
        return MODE_MQ.equals(normalizedMode());
    }

    public boolean isLocalMode() {
        return MODE_LOCAL.equals(normalizedMode());
    }

    public boolean isAutoMode() {
        return MODE_AUTO.equals(normalizedMode());
    }

    /**
     * 是否需要探活：local 模式完全不碰 MQ，无需探活
     */
    public boolean needProbe() {
        return !isLocalMode();
    }
}
