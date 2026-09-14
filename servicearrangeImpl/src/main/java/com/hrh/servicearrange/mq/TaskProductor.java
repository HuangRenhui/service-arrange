package com.hrh.servicearrange.mq;

import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.vo.Task4MQ;
import com.rabbitmq.client.Channel;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 任务生产
 * ack：消费成功；
 * nack：消费失败
 */
@Component
public class TaskProductor {

    @Autowired
    private MqChannelProcessor processor;
    @Value("${task.result_queue_name}")
    private String taskResult_queue_name;
    @Value("${task.run_queue_name}")
    private String taskRun_queue_name;
    @Autowired
    private TaskRunConsumer taskRunConsumer;
    @Autowired
    private TaskResultConsumer taskResultConsumer;
    @Autowired
    private InstLogDao instLogDao;

    @Autowired
    CuratorFramework curatorFramework;

    public void sendTaskResult(Task task) {
        //日志记录
        InstLog instLog = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO);
        //消费
        taskResultConsumer.run(task.getId(), task.getInstId(), task.getState(), task.getOutputs(), task.getRetryRules(), null, null, null);
        instLogDao.save(instLog);
    }

    public void sendTaskRun(Task task) {
        InstLog instLog = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO);
        Task4MQ task4MQ = new Task4MQ(task.getId(), task.getType());
        taskRunConsumer.run(task4MQ, null, null);
        instLogDao.save(instLog);
    }

    public void ackTask(String instId, Long deliveryTag, Channel channel, String msg) {
        try {
            curatorFramework.delete().forPath(Inst.ZK_LOCK_PREFIX + instId);
            this.ackTask(deliveryTag, channel, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void nAckTask(String instId, Long deliveryTag, Channel channel, String msg) {
        try {
//            curatorFramework.delete().forPath(Inst.ZK_LOCK_PREFIX + instId);
            this.nAckTask(deliveryTag, channel, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
