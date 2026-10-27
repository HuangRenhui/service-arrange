package com.hrh.servicearrange.mq;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Task;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.context.annotation.Conditional;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MQ 结果队列监听（result channel），承担跨节点实例锁。
 * <p>
 * {@code task.dispatch.mode=local} 时整个 Bean 不装配；本地模式下实例串行由
 * {@link TaskDispatcher} 的 instId 分片线程保证，不依赖 ZooKeeper。
 */
@Slf4j
@Component
@EnableBinding(MqChannelProcessor.class)
@Conditional(OnMqDispatchCondition.class)
public class MqTaskResultListener {

    @Autowired
    private TaskResultConsumer taskResultConsumer;

    @Autowired
    private TaskProductor taskProductor;

    @Autowired
    private CuratorFramework curatorFramework;

    @Autowired
    private InstanceLock instanceLock;

    @StreamListener(target = MqChannelProcessor.TASK_RESULT_INPUT)
    @RabbitHandler
    public void onMessage(Message<?> message,
                          @Header(AmqpHeaders.CHANNEL) Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) {
        log.info("task result receive: {}", message);
        //MQ 投递的 payload 可能是 byte[] 或 String，统一转文本后再解析
        Object rawPayload = message.getPayload();
        JSONObject jsonObject = JSONUtil.parseObj(rawPayload instanceof byte[]
                ? new String((byte[]) rawPayload, StandardCharsets.UTF_8) : String.valueOf(rawPayload));
        String id = jsonObject.getStr("id");
        String instId = jsonObject.getStr("instId");
        String state = jsonObject.getStr("state");
        Task.Result outputs = JSONUtil.toBean(jsonObject.getJSONObject("outputs"), Task.Result.class);
        Task.RetryRules retryRules = JSONUtil.toBean(jsonObject.getJSONObject("retryRules"), Task.RetryRules.class);
        if (StrUtil.isBlank(instId)) {
            taskProductor.ackTask(deliveryTag, channel, "ack, instId is empty:" + jsonObject);
            return;
        }
        String lockNode = null;
        try {
            lockNode = curatorFramework.create().withMode(CreateMode.EPHEMERAL)
                    .forPath(Inst.ZK_LOCK_PREFIX + instId, instId.getBytes());
        } catch (Exception e) {
            log.error("acquire inst lock fail, instId={}", instId, e);
        }
        if (StrUtil.isNotEmpty(lockNode)) {
            //进程内实例锁：与本地直连分片线程互斥，避免切换瞬间同实例被并行推进
            ReentrantLock lock = instanceLock.lockOf(instId);
            lock.lock();
            try {
                taskResultConsumer.run(id, instId, state, outputs, retryRules, channel, deliveryTag, lockNode);
            } finally {
                lock.unlock();
            }
        } else {
            //抢锁失败：走带次数上限与退避的重排队，避免无限 requeue 导致的消息热点空转
            taskProductor.nAckLockTask(id, instId, deliveryTag, channel, "get inst lock fail,do nack.");
        }
    }
}
