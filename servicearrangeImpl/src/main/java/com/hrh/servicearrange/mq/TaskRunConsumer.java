package com.hrh.servicearrange.mq;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 任务消费，执行具体任务
 */
@Component
@EnableBinding(MqChannelProcessor.class)
public class TaskRunConsumer {

    @Value("${task.run_queue_name}")
    private String taskRun_queue_name;

    @StreamListener(target = MqChannelProcessor.TASK_RUN_INPUT)
    @RabbitHandler
    public void listenInstTaskResult(Message<?> message,
                                     @Header(AmqpHeaders.CHANNEL) Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) {
    }


}
