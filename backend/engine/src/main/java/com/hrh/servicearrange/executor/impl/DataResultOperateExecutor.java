package com.hrh.servicearrange.executor.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service(CellType.FUN_DATARESULT)
public class DataResultOperateExecutor implements Execute {

    @Autowired
    private DataMapOperateExecutor dataMapOperateExecutor;
    @Autowired
    private StartOperateExecutor startOperateExecutor;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        try {
            JSONObject inputs = JSONUtil.parseObj(task.getInputs());
            JSONArray rules = inputs.getJSONArray("resultRules");
            JSONObject pouts = JSONUtil.createObj();
            Task startTask = taskDao.findStartTask(task.getInstId(), CellType.START);
            if (startTask != null) {
                pouts.set(startTask.getNodeId(), startTask.getOutputs());
                startOperateExecutor.getDataByNodeIds(task, inst, taskDao, task.getInputs(), pouts);
            }
            JSONObject out = JSONUtil.createObj();
            if (rules != null) {
                String startId = startTask == null ? null : startTask.getNodeId();
                for (int i = 0; i < rules.size(); i++) {
                    JSONObject rule = rules.getJSONObject(i);
                    if (rule == null || StringUtils.isEmpty(rule.getStr("key"))) {
                        continue;
                    }
                    Object value = null;
                    String mapping = rule.getStr("jsonpathMapping");
                    if (!StringUtils.isEmpty(mapping) && startId != null) {
                        value = dataMapOperateExecutor.valueCompute(startId, mapping, pouts, task, instLogDao);
                    }
                    out.set(rule.getStr("key"), value);
                }
            }
            task.getOutputs().setContentType("application/json");
            task.getOutputs().setValue(JSONUtil.toJsonStr(out));
            task.setState(Task.STATE_SUCCESS);
        } catch (Exception e) {
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "数据结果失败：" + e.getMessage()));
        }
        return task;
    }
}
