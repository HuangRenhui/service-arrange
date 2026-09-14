package com.hrh.servicearrange.executor;

import cn.hutool.extra.spring.SpringUtil;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.mq.TaskProductor;

import java.util.Date;

/**
 * @author huangrenhui
 * @date 2022/4/8
 * @flow 任务执行器
 */
public interface Execute {
    /**
     *
     * @param tid 任务id
     */
    default void run(String tid){
        TaskDao taskDao = SpringUtil.getBean(TaskDao.class);
        InstDao instDao = SpringUtil.getBean(InstDao.class);
        InstLogDao instLogDao = SpringUtil.getBean(InstLogDao.class);
        TaskProductor taskProductor = SpringUtil.getBean(TaskProductor.class);
        Task task = taskDao.findById(tid).orElse(null);
        if(task==null){
            task = new Task();
            task.setId(tid);
            task.setState(Task.STATE_FAIL);
            task.setExecutorStartDate(new Date());
        }else {
            task.setState(Task.STATE_RUNNING);
            Inst inst = instDao.findById(task.getInstId()).orElse(null);
            task.setExecutorStartDate(new Date());
            task = runProcess(task,inst,taskDao,instLogDao);
            if(!task.getState().equals(Task.STATE_FAIL)){
                task.setState(Task.STATE_SUCCESS);
            }
        }
        task.setExecutorEndDate(new Date());
        taskProductor.sendTaskResult(task);
    }
    Task runProcess(Task task,Inst inst,TaskDao taskDao,InstLogDao instLogDao);
}
