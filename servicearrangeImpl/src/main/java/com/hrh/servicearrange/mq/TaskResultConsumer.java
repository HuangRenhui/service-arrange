package com.hrh.servicearrange.mq;

/**
 * @author huangrenhui
 * @date 2022/3/9
 * @flow
 */

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


@Component
@EnableBinding(MqChannelProcessor.class)
public class TaskResultConsumer {

    @Autowired
    private MqChannelProcessor processor;
    @Value("${task.result_queue_name}")
    private String taskResult_queue_name;

    @StreamListener(target = MqChannelProcessor.TASK_RESULT_INPUT)
    @RabbitHandler
    public void listenInstTaskResult(Message<?> message,
                                     @Header(AmqpHeaders.CHANNEL) Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) {
        JSONObject jsonObj = null;
        try {
            System.out.println("接收到【实例任务结果】状态信息  : " + message.toString());
            jsonObj = JSONUtil.parseObj(message.getPayload());
            if (null == jsonObj && message.getPayload() instanceof byte[]) {
                Message<byte[]> messageByte = (Message<byte[]>) message;
                String json = new String(messageByte.getPayload(), CharsetUtil.CHARSET_UTF_8);
                jsonObj = JSONUtil.parseObj(json);
            }
            Thread.sleep(5000);
            //手工ack
            channel.basicAck(deliveryTag, true);
        } catch (Exception e) {
            if(channel.isOpen()) {
                try {
                    channel.basicNack(deliveryTag, false,true);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
            e.printStackTrace();
        }
        System.out.println("receive--2: " + jsonObj.toString());
    }

    public void sendTaskResult(Object obj) {
        Map<String, Object> properties2 = new HashMap<>();
        properties2.put("receiveSign", taskResult_queue_name);
        MessageHeaders mhs2 = new MessageHeaders(properties2);
        processor.taskProductor().send(MessageBuilder.createMessage(obj, mhs2));
    }
}
