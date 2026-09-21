package com.hrh.servicearrange.mq;

import com.hrh.servicearrange.config.BackpressureProperties;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.vo.Task4MQ;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;

/**
 * 任务生产
 * ack：消费成功；
 * nack：消费失败
 * <p>
 * 背压保护：nack 不再无条件 requeue。重排队次数超过阈值后强制 ack 丢弃，
 * 并在重排队前做退避，避免同一条消息在抢锁失败时形成热点空转。
 */
@Slf4j
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
    private InstLogDao instLogDao;
    @Autowired
    private TaskDao taskDao;
    @Autowired
    private BackpressureProperties backpressure;

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
        } catch (Exception e) {
            //锁可能已被其它路径释放，这里不作为失败处理
            log.debug("release inst lock fail, instId={}, msg={}", instId, e.getMessage());
        }
        this.ackTask(deliveryTag, channel, msg);
    }

    public void nAckTask(String instId, Long deliveryTag, Channel channel, String msg) {
        try {
            curatorFramework.delete().forPath(Inst.ZK_LOCK_PREFIX + instId);
        } catch (Exception e) {
            log.debug("release inst lock fail, instId={}, msg={}", instId, e.getMessage());
        }
        this.nAckTask(deliveryTag, channel, msg);
    }

    /**
     * 抢实例锁失败时的重排队入口：带次数上限与退避，避免无限 requeue 造成的热点空转。
     *
     * @param taskId      任务id，用于累计重排队次数
     * @param instId      实例id
     * @param deliveryTag 投递标签
     * @param channel     mq通道
     * @param msg         日志信息
     */
    public void nAckLockTask(String taskId, String instId, Long deliveryTag, Channel channel, String msg) {
        try {
            curatorFramework.delete().forPath(Inst.ZK_LOCK_PREFIX + instId);
        } catch (Exception e) {
            log.debug("release inst lock fail, instId={}, msg={}", instId, e.getMessage());
        }
        Task task = taskId == null ? null : taskDao.findById(taskId).orElse(null);
        if (task == null) {
            //任务不存在，直接丢弃，避免无意义重排队
            this.ackTask(deliveryTag, channel, msg + " task not found, do ack to avoid requeue storm.");
            return;
        }
        int times = task.getLockNackTimes() == null ? 0 : task.getLockNackTimes();
        if (times >= backpressure.getLockNackMaxTimes()) {
            //超过重排队上限，强制 ack 丢弃，防止消息在队列中永久空转
            this.ackTask(deliveryTag, channel, msg + " lockNackTimes>= " + backpressure.getLockNackMaxTimes() + ", force ack to avoid requeue storm.");
            return;
        }
        task.setLockNackTimes(times + 1);
        //记录下次可重投时间，供消费端做退避判断
        task.setNextRetryTime(new Date(System.currentTimeMillis() + backpressure.backoffMs(times + 1)));
        taskDao.save(task);
        //退避：避免立即重新入队造成的忙等
        sleepQuietly(backpressure.backoffMs(times + 1));
        this.nAckTask(deliveryTag, channel, msg + " lockNackTimes=" + (times + 1));
    }

    public void nAckTask(Long deliveryTag, Channel channel, String msg) {
        log.warn("do nack, msg={}", msg);
        if (channel != null && channel.isOpen()) {
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException e) {
                log.error("nack fail", e);
            }
        }
    }

    public void ackTask(Long deliveryTag, Channel channel, String msg) {
        log.info("do ack, msg={}", msg);
        if (channel != null && channel.isOpen()) {
            try {
                channel.basicAck(deliveryTag, false);
            } catch (IOException e) {
                log.error("ack fail", e);
            }
        }
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
