package com.hrh.servicearrange.mq;

import cn.hutool.core.util.StrUtil;
import com.hrh.servicearrange.config.BackpressureProperties;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private InstLogDao instLogDao;
    @Autowired
    private TaskDao taskDao;
    @Autowired
    private BackpressureProperties backpressure;

    @Autowired
    CuratorFramework curatorFramework;

    /**
     * 投递决策统一交给 {@link TaskDispatcher}：按 {@code task.dispatch.mode} 走 MQ 或本地直连
     */
    @Autowired
    private TaskDispatcher taskDispatcher;

    /**
     * 投递任务结果，由结果处理链路异步消费（MQ 或本地直连）。
     *
     * @param task 已执行完成的任务
     */
    public void sendTaskResult(Task task) {
        taskDispatcher.dispatchResult(task);
        instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO));
    }

    /**
     * 投递待执行任务，由任务执行链路异步消费（MQ 或本地直连）。
     *
     * @param task 待执行任务
     */
    public void sendTaskRun(Task task) {
        taskDispatcher.dispatchRun(task);
        instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO));
    }

    /**
     * 仅在本进程持有实例锁（lockNode 非空）时释放 ZK 锁，避免误删其它消费者的 ephemeral 节点。
     */
    public void ackTask(String instId, Long deliveryTag, Channel channel, String msg, String lockNode) {
        releaseLockIfHeld(instId, lockNode);
        this.ackTask(deliveryTag, channel, msg);
    }

    public void ackTask(String instId, Long deliveryTag, Channel channel, String msg) {
        ackTask(instId, deliveryTag, channel, msg, null);
    }

    public void nAckTask(String instId, Long deliveryTag, Channel channel, String msg, String lockNode) {
        releaseLockIfHeld(instId, lockNode);
        this.nAckTask(deliveryTag, channel, msg);
    }

    public void nAckTask(String instId, Long deliveryTag, Channel channel, String msg) {
        nAckTask(instId, deliveryTag, channel, msg, null);
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
        //抢锁失败时不得删除 ZK 路径：该路径属于持有锁的消费者。
        Task task = taskId == null ? null : taskDao.findById(taskId).orElse(null);
        if (task == null) {
            this.ackTask(deliveryTag, channel, msg + " task not found, do ack to avoid requeue storm.");
            return;
        }
        int times = task.getLockNackTimes() == null ? 0 : task.getLockNackTimes();
        if (times >= backpressure.getLockNackMaxTimes()) {
            this.ackTask(deliveryTag, channel, msg + " lockNackTimes>= " + backpressure.getLockNackMaxTimes() + ", force ack to avoid requeue storm.");
            return;
        }
        task.setLockNackTimes(times + 1);
        task.setNextRetryTime(new Date(System.currentTimeMillis() + backpressure.backoffMs(times + 1)));
        taskDao.save(task);
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

    private void releaseLockIfHeld(String instId, String lockNode) {
        if (StrUtil.isEmpty(instId) || StrUtil.isEmpty(lockNode)) {
            return;
        }
        try {
            curatorFramework.delete().forPath(Inst.ZK_LOCK_PREFIX + instId);
        } catch (Exception e) {
            log.debug("release inst lock fail, instId={}, msg={}", instId, e.getMessage());
        }
    }
}
