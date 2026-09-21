package com.hrh.servicearrange.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 背压保护配置。
 * <p>
 * 当前引擎的缓冲被外包给 RabbitMQ，消费端默认串行（concurrency=1）。
 * 若不做任何限制，慢下游 + 抢锁失败重排队会造成「消息热点空转」与「消费线程被长期占用」。
 * 本配置集中管理三类保护阈值：
 * <ol>
 *     <li>消息重排队次数上限：防止同一条消息在抢锁失败时被无限 requeue；</li>
 *     <li>重排队退避时间：避免立即重新入队造成的忙等；</li>
 *     <li>消费并发数：在 broker 未堆积与吞吐之间取平衡。</li>
 * </ol>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "task.backpressure")
public class BackpressureProperties {

    /**
     * 抢实例锁失败时允许的最大重排队次数，超过后强制 ack 丢弃该条消息，防止无限 requeue。
     */
    private int lockNackMaxTimes = 10;

    /**
     * 抢锁失败后重排队前的退避基数，单位毫秒；实际退避 = base * 已重排队次数（线性退避，封顶 maxBackoffMs）。
     */
    private long requeueBackoffBaseMs = 200L;

    /**
     * 单次退避的上限，单位毫秒。
     */
    private long requeueBackoffMaxMs = 5000L;

    /**
     * 单条消息允许的最大处理次数（含业务 nack 与抢锁 nack），超过后不再重排队。
     */
    private int maxHandleTimes = 5;

    /**
     * 消息重排队时是否延迟重投（true 时基于 nextRetryTime 判断是否还没到点，未到点则直接 ack 放弃当前投递，由重投承担）。
     */
    private boolean delayRequeueEnabled = true;

    /**
     * 计算下一次退避时长（线性递增并封顶）。
     *
     * @param nackTimes 已经重排队的次数
     * @return 退避毫秒数
     */
    public long backoffMs(int nackTimes) {
        long backoff = requeueBackoffBaseMs * Math.max(1, nackTimes);
        return Math.min(backoff, requeueBackoffMaxMs);
    }
}
