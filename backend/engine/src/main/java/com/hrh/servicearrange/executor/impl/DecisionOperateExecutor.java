package com.hrh.servicearrange.executor.impl;

import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.stereotype.Service;

@Service(CellType.FUN_DECISION)
public class DecisionOperateExecutor implements Execute {
    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        task.setState(Task.STATE_SUCCESS);
        task.getOutputs().setValue(task.getInputs());
        task.getOutputs().setContentType("application/json");
        instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO, "决策节点通过，分支由决策线判定"));
        return task;
    }
}
