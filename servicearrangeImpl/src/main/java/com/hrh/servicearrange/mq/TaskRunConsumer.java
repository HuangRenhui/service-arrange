package com.hrh.servicearrange.mq;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import com.hrh.servicearrange.utils.SpringBeanUtils;
import com.hrh.servicearrange.vo.Task4MQ;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

/**
 * 任务消费，执行具体任务
 */
@Component
@EnableBinding(MqChannelProcessor.class)
public class TaskRunConsumer {

    @Autowired
    private TaskDao taskDao;
    @Autowired
    private TaskProductor taskProductor;

    @StreamListener(target = MqChannelProcessor.TASK_RUN_INPUT)
    @RabbitHandler
    public void listenInstTaskResult(Message<?> message,
                                     @Header(AmqpHeaders.CHANNEL) Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) {
        System.out.println("TaskRunConsumer 接收到【实例任务执行】状态信息  : " + message.toString());
        JSONObject jsonObj = JSONUtil.parseObj(message.getPayload());

        //手工ack
        String id = jsonObj.get("id", String.class);
        String type = jsonObj.get("type", String.class);
        if (StringUtils.isEmpty(id) || StringUtils.isEmpty(type)) {
            taskProductor.ackTask(deliveryTag, channel, "ack，params can't be null:" + jsonObj.toString());
            return;
        }
        run(new Task4MQ(id, type), channel, deliveryTag);
    }


    public void run(Task4MQ task4MQ, Channel channel, Long deliveryTag) {
        Task task = null;
        try {
            task = taskDao.findById(task4MQ.getId()).orElse(null);
            if (task == null) {
                taskProductor.ackTask(deliveryTag, channel, "ack,can't find the task:" + task4MQ.getId());
                return;
            }
            if (task.getRunNackTimes() >= 5) {
                taskProductor.ackTask(deliveryTag, channel, "ack,runNackTimes>=5,this message may never be ack,forece ack this message.");
                return;
            }
            Execute execute = null;
            if (Arrays.asList(CellType.FUN_JAR, CellType.FUN_PYTHON).contains(task.getType())) {
                execute = (Execute) SpringBeanUtils.getBean(CellType.FUN_SHELL);
            } else {
                execute = (Execute) SpringBeanUtils.getBean(task.getType());
            }
            if (execute != null) {
                execute.run(task.getId());
                taskProductor.ackTask(deliveryTag, channel, "success run");
            } else {
                task.setRunNackTimes(task.getRunNackTimes() + 1);
                taskProductor.nAckTask(deliveryTag, channel, "error run");
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (task != null) {
                task.setRunNackTimes(task.getRunNackTimes() + 1);
                taskDao.save(task);
                taskProductor.nAckTask(deliveryTag, channel, "Exception");
            }
        }
    }
}
