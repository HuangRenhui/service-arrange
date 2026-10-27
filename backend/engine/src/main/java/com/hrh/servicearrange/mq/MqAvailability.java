package com.hrh.servicearrange.mq;

import cn.hutool.core.util.StrUtil;
import com.hrh.servicearrange.config.TaskDispatchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RabbitMQ 可达性探活。
 * <p>
 * Spring Cloud Stream 在 RabbitMQ 不可达时只会后台重连、不会主动暴露状态，因此这里用 TCP 连接自行探测：
 * 启动后探一次，之后按 {@code task.dispatch.probe-interval-ms} 周期复核，断连 / 恢复都会打日志。
 * <p>
 * {@code task.dispatch.mode=local} 时完全不探活，也不创建任何 MQ 连接。
 */
@Slf4j
@Component
public class MqAvailability {

    @Autowired
    private TaskDispatchProperties dispatchProperties;

    /**
     * binder 环境中的地址；binder 名称变化时请改用 task.dispatch.address 显式指定
     */
    @Value("${spring.cloud.stream.binders.rabbit_cluster.environment.spring.rabbitmq.addresses:${spring.rabbitmq.addresses:}}")
    private String binderAddresses;

    @Value("${spring.rabbitmq.host:localhost}")
    private String rabbitHost;

    @Value("${spring.rabbitmq.port:5672}")
    private int rabbitPort;

    private final AtomicBoolean available = new AtomicBoolean(false);

    private volatile String lastError;

    private ScheduledExecutorService scheduler;

    /**
     * 启动期同步探活：保证第一笔请求进来前就确定「能否走 MQ」，
     * MQ 连不上时直接以本地直连模式提供服务。
     */
    @PostConstruct
    public void init() {
        if (!dispatchProperties.needProbe()) {
            log.info("task dispatch mode=local, 跳过 MQ 启动探活（完全本地直连）");
            return;
        }
        boolean reachable = probeNow();
        if (reachable) {
            log.info("启动探活：MQ 可达，任务走 MQ 投递");
        } else {
            log.warn("启动探活：MQ 不可达（{}），按 task.dispatch.degrade-to-local={} 决定降级，当前将走本地直连",
                    lastError, dispatchProperties.isDegradeToLocal());
        }
    }

    /**
     * 启动后按间隔持续探测（auto 模式用于「恢复后自动切回」）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!dispatchProperties.needProbe()) {
            log.info("task dispatch mode=local, 不创建 MQ 探活（完全本地直连）");
            return;
        }
        long interval = Math.max(1000L, dispatchProperties.getProbeIntervalMs());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "servea-mq-probe");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::probeQuietly, 0, interval, TimeUnit.MILLISECONDS);
        log.info("MQ 探活已启动, address={}, interval={}ms", resolveAddress(), interval);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public boolean isAvailable() {
        return available.get();
    }

    public String getLastError() {
        return lastError;
    }

    /**
     * 投递失败时由 {@link TaskDispatcher} 调用，立即标记不可用
     */
    public void markUnavailable(String reason) {
        lastError = reason;
        if (available.getAndSet(false)) {
            log.warn("MQ 标记为不可用：{}，后续任务将降级本地直连", reason);
        } else {
            log.debug("MQ 仍不可用：{}", reason);
        }
    }

    /**
     * 立即探活一次，返回当前是否可达
     */
    public boolean probeNow() {
        String address = resolveAddress();
        String host;
        int port;
        int index = address.lastIndexOf(':');
        if (index > 0) {
            host = address.substring(0, index);
            port = parseInt(address.substring(index + 1), 5672);
        } else {
            host = address;
            port = 5672;
        }
        int timeout = Math.max(200, dispatchProperties.getProbeTimeoutMs());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            boolean previous = available.getAndSet(true);
            lastError = null;
            if (!previous) {
                log.info("MQ 可达（自动切回 MQ 投递）, address={}:{}", host, port);
            }
            return true;
        } catch (Exception e) {
            lastError = e.getMessage();
            boolean previous = available.getAndSet(false);
            if (previous) {
                log.warn("MQ 不可达，任务降级本地直连, address={}:{}, reason={}", host, port, e.getMessage());
            } else {
                log.debug("MQ 探活失败, address={}:{}, reason={}", host, port, e.getMessage());
            }
            return false;
        }
    }

    private void probeQuietly() {
        try {
            probeNow();
        } catch (Exception e) {
            //探活线程不允许抛出，否则 scheduleWithFixedDelay 会终止
            log.error("MQ 探活异常", e);
        }
    }

    /**
     * 地址解析优先级：task.dispatch.address → binder 配置 → spring.rabbitmq.host/port
     */
    private String resolveAddress() {
        if (StrUtil.isNotBlank(dispatchProperties.getAddress())) {
            return normalize(dispatchProperties.getAddress());
        }
        if (StrUtil.isNotBlank(binderAddresses)) {
            String first = binderAddresses.split(",")[0];
            if (StrUtil.isNotBlank(first)) {
                return normalize(first);
            }
        }
        return normalize(rabbitHost + ":" + rabbitPort);
    }

    /**
     * 去掉协议前缀与用户名密码，只保留 host:port
     */
    private String normalize(String raw) {
        String address = raw.trim();
        int schemeIndex = address.indexOf("://");
        if (schemeIndex >= 0) {
            address = address.substring(schemeIndex + 3);
        }
        int atIndex = address.lastIndexOf('@');
        if (atIndex >= 0) {
            address = address.substring(atIndex + 1);
        }
        int slashIndex = address.indexOf('/');
        if (slashIndex >= 0) {
            address = address.substring(0, slashIndex);
        }
        return address;
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
