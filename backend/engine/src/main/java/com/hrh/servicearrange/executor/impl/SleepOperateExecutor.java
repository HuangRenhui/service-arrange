package com.hrh.servicearrange.executor.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.stereotype.Service;

@Service(CellType.FUN_SLEEP)
public class SleepOperateExecutor implements Execute {

    private static final long MAX_MS = 60_000L;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        long ms = 1000L;
        try {
            JSONObject obj = JSONUtil.parseObj(task.getInputs());
            Long msVal = obj.getLong("milliseconds");
            if (msVal == null) {
                msVal = obj.getLong("timeout");
            }
            ms = msVal == null ? 1000L : msVal;
        } catch (Exception ignored) {
        }
        ms = Math.max(0, Math.min(ms, MAX_MS));
        try {
            Thread.sleep(ms);
            task.setState(Task.STATE_SUCCESS);
            task.getOutputs().setValue("{\"slept\":" + ms + "}");
            task.getOutputs().setContentType("application/json");
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO, "sleep " + ms + "ms"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.setState(Task.STATE_FAIL);
        }
        return task;
    }
}
