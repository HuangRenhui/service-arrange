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

@Service(CellType.OPERATOR_WEBSERVICE)
public class WebServiceOperateExecutor implements Execute {

    @Value("${task.http.read-timeout:30000}")
    private int readTimeout;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        try {
            JSONObject inputs = JSONUtil.parseObj(task.getInputs());
            String url = first(inputs, "url", "serviceUrl");
            String soap = first(inputs, "soap", "body", "script", "reqBodyOther");
            if (StringUtils.isEmpty(url)) {
                task.setState(Task.STATE_FAIL);
                instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "WebService 缺少 url"));
                return task;
            }
            HttpResponse resp = HttpRequest.post(url)
                    .header("Content-Type", "text/xml;charset=UTF-8")
                    .body(soap == null ? "" : soap)
                    .timeout(readTimeout)
                    .execute();
            task.getOutputs().setContentType("application/xml");
            task.getOutputs().setValue(resp.body());
            task.setState(resp.getStatus() >= 200 && resp.getStatus() < 300 ? Task.STATE_SUCCESS : Task.STATE_FAIL);
        } catch (Exception e) {
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "WebService 调用失败：" + e.getMessage()));
        }
        return task;
    }

    private static String first(JSONObject obj, String... keys) {
        for (String k : keys) {
            String v = obj.getStr(k);
            if (!StringUtils.isEmpty(v)) {
                return v;
            }
        }
        return null;
    }
}
