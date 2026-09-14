package com.hrh.servicearrange.mq;

/**
 * @author huangrenhui
 * @date 2022/3/9
 * @flow
 */

import cn.hutool.core.util.StrUtil;
import com.rabbitmq.client.Channel;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 任务执行完后继续处理下一个节点流程
 */
@Component
@EnableBinding(MqChannelProcessor.class)
public class TaskResultConsumer {
    @Autowired
    private TaskProductor taskProductor;


    @Value("${task.result_queue_name}")
    private String taskResult_queue_name;

    @Autowired
    CuratorFramework curatorFramework;

    @StreamListener(target = MqChannelProcessor.TASK_RESULT_INPUT)
    @RabbitHandler
    public void listenInstTaskResult(Message<?> message,
                                     @Header(AmqpHeaders.CHANNEL) Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) {
        String lockNode = null;
        //加锁
        try {
            lockNode = curatorFramework.create()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath("");
        } catch (Exception e) {
            e.printStackTrace();
        }
        //加锁成功进行任务生产
        if (StrUtil.isNotEmpty(lockNode)) {
        } else {
            //任务执行完解锁
        }
    }

}
