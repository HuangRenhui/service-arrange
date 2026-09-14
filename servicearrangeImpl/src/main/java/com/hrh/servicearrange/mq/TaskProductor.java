package com.hrh.servicearrange.mq;

import com.rabbitmq.client.Channel;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 任务生产
 */
@Component
public class TaskProductor {

    @Value("${task.result_queue_name}")
    private String taskResult_queue_name;
    @Value("${task.run_queue_name}")
    private String taskRun_queue_name;
    @Autowired
    private TaskRunConsumer taskRunConsumer;
    @Autowired
    private TaskResultConsumer taskResultConsumer;

    @Autowired
    CuratorFramework curatorFramework;

    public void sendTaskResult() {

    }

    public void sendTaskRun() {
    }

    public void ackTask(String instId, Long deliveryTag, Channel channel, String msg) {

    }

    public void nAckTask(Long deliveryTag, Channel channel, String msg) {
        System.out.println(msg);
        if (channel.isOpen()) {
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void ackTask(Long deliveryTag, Channel channel, String msg) {
        System.out.println(msg);
        if (channel.isOpen()) {
            try {
                channel.basicAck(deliveryTag, false);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
