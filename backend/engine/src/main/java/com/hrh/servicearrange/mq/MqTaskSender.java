package com.hrh.servicearrange.mq;

import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.vo.Task4MQ;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * MQ 发送端：只负责把任务投到 RabbitMQ，模式判定由 {@link TaskDispatcher} 负责。
 * <p>
 * {@code task.dispatch.mode=local} 时不会有 {@link MqChannelProcessor} Bean（未装配 binder），
 * 因此这里用 {@code required=false} 注入，调用方通过 {@link #isChannelReady()} 判断。
 */
@Slf4j
@Component
public class MqTaskSender {

    @Value("${task.result_queue_name}")
    private String taskResultQueueName;

    @Value("${task.run_queue_name}")
    private String taskRunQueueName;

    @Autowired(required = false)
    private MqChannelProcessor mqChannelProcessor;

    public boolean isChannelReady() {
        return mqChannelProcessor != null;
    }

    /**
     * 投递任务结果到 result 队列，由 {@link MqTaskResultListener} 消费
     */
    public void sendResult(Task task) {
        publish(JSONUtil.toJsonStr(task), taskResultQueueName);
    }

    /**
     * 投递待执行任务到 run 队列，由 {@link MqTaskRunListener} 消费
     */
    public void sendRun(Task4MQ task4MQ) {
        publish(JSONUtil.toJsonStr(task4MQ), taskRunQueueName);
    }

    /**
     * payload 用 text/plain 承载 JSON 字符串，避免 application/json 转换器把字符串再包一层引号；
     * 路由键对应 binding 的 {@code routing-key-expression=headers.receiveSign}。
     */
    private void publish(String payload, String routingKey) {
        if (mqChannelProcessor == null) {
            throw new IllegalStateException("MQ 通道未装配（task.dispatch.mode=local），routingKey=" + routingKey);
        }
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader("receiveSign", routingKey)
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();
        boolean sent = mqChannelProcessor.taskProductor().send(message);
        if (!sent) {
            throw new IllegalStateException("MQ 投递被拒绝，routingKey=" + routingKey + "，payload=" + payload);
        }
    }
}
