package com.hrh.servicearrange.mq;

import com.hrh.servicearrange.config.BackpressureProperties;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.CellTypeMapper;
import com.hrh.servicearrange.utils.SpringBeanUtils;
import com.hrh.servicearrange.vo.Task4MQ;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 任务执行入口：执行具体任务。
 * <p>
 * 不再直接绑定 MQ 通道——MQ 监听在 {@link MqTaskRunListener}（仅在 mq / auto 模式装配），
 * 本地直连由 {@link TaskDispatcher} 调用 {@link #runLocal(Task4MQ)}。
 */
@Slf4j
@Component
public class TaskRunConsumer {

    @Autowired
    private TaskDao taskDao;
    @Autowired
    private TaskProductor taskProductor;
    @Autowired
    private BackpressureProperties backpressure;

    /**
     * 本地直连入口：不经过 MQ，没有 channel / deliveryTag。
     * <p>
     * ack / nack 内部已判空 channel，因此传 null 安全；本地模式不做 MQ 重投，
     * 失败重试由 {@code Task.RetryRules} / {@code runNackTimes} 与结果推进逻辑控制。
     */
    public void runLocal(Task4MQ task4MQ) {
        run(task4MQ, null, null);
    }

    public void run(Task4MQ task4MQ, Channel channel, Long deliveryTag) {
        Task task = null;
        try {
            task = taskDao.findById(task4MQ.getId()).orElse(null);
            if (task == null) {
                taskProductor.ackTask(deliveryTag, channel, "ack,can't find the task:" + task4MQ.getId());
                return;
            }
            //超过重试上限，强制 ack 丢弃，避免毒消息永久占用消费线程（当前消费并发为 1，占用即阻塞整条链路）
            int runNackTimes = task.getRunNackTimes() == null ? 0 : task.getRunNackTimes();
            if (runNackTimes >= backpressure.getMaxHandleTimes()) {
                taskProductor.ackTask(deliveryTag, channel, "ack,runNackTimes>=" + backpressure.getMaxHandleTimes() + ",this message may never be ack,forece ack this message.");
                return;
            }
            Execute execute = (Execute) SpringBeanUtils.getBeanOrNull(CellTypeMapper.resolveExecuteType(task));
            if (execute != null) {
                execute.run(task.getId());
                taskProductor.ackTask(deliveryTag, channel, "success run");
            } else {
                int nack = task.getRunNackTimes() == null ? 0 : task.getRunNackTimes();
                task.setRunNackTimes(nack + 1);
                task.setState(Task.STATE_FAIL);
                taskDao.save(task);
                taskProductor.nAckTask(deliveryTag, channel, "error run: no executor for type " + task.getType());
            }
        } catch (Exception e) {
            log.error("run task fail, taskId={}", task4MQ.getId(), e);
            if (task != null) {
                int nack = task.getRunNackTimes() == null ? 0 : task.getRunNackTimes();
                task.setRunNackTimes(nack + 1);
                taskDao.save(task);
                taskProductor.nAckTask(deliveryTag, channel, "Exception");
            } else {
                taskProductor.ackTask(deliveryTag, channel, "Exception with missing task, do ack.");
            }
        }
    }
}
