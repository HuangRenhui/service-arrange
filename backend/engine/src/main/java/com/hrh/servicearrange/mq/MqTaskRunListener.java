package com.hrh.servicearrange.mq;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.vo.Task4MQ;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.context.annotation.Conditional;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * MQ 运行队列监听（run channel）。
 * <p>
 * {@code task.dispatch.mode=local} 时整个 Bean 不装配，也就不存在 binder / RabbitMQ 连接；
 * 任务由 {@link TaskDispatcher} 本地直连 {@link TaskRunConsumer#runLocal(Task4MQ)}。
 */
@Slf4j
@Component
@EnableBinding(MqChannelProcessor.class)
@Conditional(OnMqDispatchCondition.class)
public class MqTaskRunListener {

    @Autowired
    private TaskRunConsumer taskRunConsumer;

    @Autowired
    private TaskProductor taskProductor;

    @StreamListener(target = MqChannelProcessor.TASK_RUN_INPUT)
    @RabbitHandler
    public void onMessage(Message<?> message,
                          @Header(AmqpHeaders.CHANNEL) Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) {
        log.info("task run receive: {}", message);
        //MQ 投递的 payload 可能是 byte[] 或 String，统一转文本后再解析
        Object rawPayload = message.getPayload();
        JSONObject jsonObj = JSONUtil.parseObj(rawPayload instanceof byte[]
                ? new String((byte[]) rawPayload, StandardCharsets.UTF_8) : String.valueOf(rawPayload));

        String id = jsonObj.get("id", String.class);
        String type = jsonObj.get("type", String.class);
        if (StringUtils.isEmpty(id) || StringUtils.isEmpty(type)) {
            taskProductor.ackTask(deliveryTag, channel, "ack，params can't be null:" + jsonObj);
            return;
        }
        taskRunConsumer.run(new Task4MQ(id, type), channel, deliveryTag);
    }
}
