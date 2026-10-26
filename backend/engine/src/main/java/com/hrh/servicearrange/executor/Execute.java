package com.hrh.servicearrange.executor;

import cn.hutool.extra.spring.SpringUtil;
import com.hrh.servicearrange.config.OrchestrationProperties;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.mq.TaskProductor;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author huangrenhui
 * @date 2022/4/8
 * @flow 任务执行器
 */
public interface Execute {

    ExecutorService TASK_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "servea-task-exec");
        t.setDaemon(true);
        return t;
    });

    /**
     *
     * @param tid 任务id
     */
    default void run(String tid){
        TaskDao taskDao = SpringUtil.getBean(TaskDao.class);
        InstDao instDao = SpringUtil.getBean(InstDao.class);
        InstLogDao instLogDao = SpringUtil.getBean(InstLogDao.class);
        TaskProductor taskProductor = SpringUtil.getBean(TaskProductor.class);
        OrchestrationProperties orch = SpringUtil.getBean(OrchestrationProperties.class);
        Task task = taskDao.findById(tid).orElse(null);
        if (task == null) {
            return;
        }
        task.setExecutorStartDate(new Date());
        Inst inst = instDao.findById(task.getInstId()).orElse(null);
        if (inst == null || Inst.STATE_CANCEL.equals(inst.getState())) {
            task.setState(Task.STATE_FAIL);
            task.setExecutorEndDate(new Date());
            taskDao.save(task);
            if (inst != null) {
                taskProductor.sendTaskResult(task);
            }
            return;
        }
        task.setState(Task.STATE_RUNNING);
        long timeoutMs = orch.getTaskTimeoutMs();
        if (task.getRetryRules() != null && task.getRetryRules().getTimeoutMs() != null
                && task.getRetryRules().getTimeoutMs() > 0) {
            timeoutMs = task.getRetryRules().getTimeoutMs();
        } else if (inst.getPlanVersion() != null && orch.getTaskTimeoutMs() > 0) {
            timeoutMs = orch.getTaskTimeoutMs();
        }
        Future<Task> future = null;
        try {
            Task running = task;
            Inst runningInst = inst;
            future = TASK_POOL.submit(() -> runProcess(running, runningInst, taskDao, instLogDao));
            task = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            //执行超时通常是下游变慢或负载过高，按可重试处理，由 RetryRules 限定次数
            task.setErrorClass(ErrorClass.RETRYABLE.name());
            task.setErrorCode("TIMEOUT");
            task.setErrorMessage("任务超时 " + timeoutMs + "ms");
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(),
                    InstLog.LEVEL_ERRO, "任务超时 " + timeoutMs + "ms"));
        } catch (Exception e) {
            //执行器未产出分类时的兜底：按 retryable-exceptions 前缀匹配，未命中则按 default-action
            ErrorClassifier.Verdict verdict = SpringUtil.getBean(ErrorClassifier.class).classify(task, null, e);
            task.setErrorClass(verdict.getErrorClass().name());
            task.setErrorCode(verdict.getCode());
            task.setErrorMessage(verdict.getMessage());
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(),
                    InstLog.LEVEL_ERRO, "任务执行异常：" + e.getMessage()));
        }
        if (task != null && !Task.STATE_FAIL.equals(task.getState())) {
            task.setState(Task.STATE_SUCCESS);
        }
        if (task != null) {
            task.setExecutorEndDate(new Date());
            //必须先落库：消费端 TaskResultConsumer 是 findById 重读 Task，
            //不落库则执行器设置的 errorClass / errorCode 会丢失
            taskDao.save(task);
            taskProductor.sendTaskResult(task);
        }
    }
    Task runProcess(Task task,Inst inst,TaskDao taskDao,InstLogDao instLogDao);
}
