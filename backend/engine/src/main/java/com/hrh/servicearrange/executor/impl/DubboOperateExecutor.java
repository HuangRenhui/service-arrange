package com.hrh.servicearrange.executor.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service(CellType.OPERATOR_DUBBO)
public class DubboOperateExecutor implements Execute {

    @Value("${task.http.read-timeout:30000}")
    private int readTimeout;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        try {
            JSONObject inputs = JSONUtil.parseObj(task.getInputs());
            String url = inputs.getStr("url", inputs.getStr("serviceUrl"));
            if (!StringUtils.isEmpty(url) && url.startsWith("http")) {
                HttpResponse resp = HttpRequest.post(url).body(task.getInputs()).timeout(readTimeout).execute();
                task.getOutputs().setContentType("application/json");
                task.getOutputs().setValue(resp.body());
                task.setState(resp.getStatus() >= 200 && resp.getStatus() < 300 ? Task.STATE_SUCCESS : Task.STATE_FAIL);
                return task;
            }
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO,
                    "Dubbo 泛化调用未配置 HTTP 网关地址（url/serviceUrl），请填写可调用的 HTTP 入口"));
        } catch (Exception e) {
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "Dubbo 节点失败：" + e.getMessage()));
        }
        return task;
    }
}
