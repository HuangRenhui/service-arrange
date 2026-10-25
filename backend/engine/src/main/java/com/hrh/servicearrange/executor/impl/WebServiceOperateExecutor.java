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
import com.hrh.servicearrange.executor.ErrorClass;
import com.hrh.servicearrange.executor.ErrorClassifier;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service(CellType.OPERATOR_WEBSERVICE)
public class WebServiceOperateExecutor implements Execute {

    @Value("${task.http.read-timeout:30000}")
    private int readTimeout;

    /**
     * 错误码分类器：把 HTTP 状态码 / SOAP Fault 映射为 SUCCESS / RETRYABLE / FATAL
     */
    @Autowired
    private ErrorClassifier errorClassifier;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        try {
            JSONObject inputs = JSONUtil.parseObj(task.getInputs());
            String url = first(inputs, "url", "serviceUrl");
            String soap = first(inputs, "soap", "body", "script", "reqBodyOther");
            if (StringUtils.isEmpty(url)) {
                //配置缺失属于不可恢复的错误，直接判 FATAL，避免无意义重试
                task.setErrorClass(ErrorClass.FATAL.name());
                task.setErrorCode("URL_MISSING");
                task.setErrorMessage("WebService 缺少 url");
                task.setState(Task.STATE_FAIL);
                instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "WebService 缺少 url"));
                return task;
            }
            HttpResponse resp = HttpRequest.post(url)
                    .header("Content-Type", "text/xml;charset=UTF-8")
                    .body(soap == null ? "" : soap)
                    .timeout(readTimeout)
                    .execute();
            String body = resp.body();
            task.getOutputs().setContentType("application/xml");
            task.getOutputs().setValue(body);
            //按 task.error-mapping.webservice 判定：默认 2xx 成功，可配 soap-fault 识别 SOAP 错误码
            ErrorClassifier.Verdict verdict = errorClassifier.classify("webservice", resp.getStatus(), body, null);
            task.setErrorClass(verdict.getErrorClass().name());
            task.setErrorCode(verdict.getCode());
            task.setErrorMessage(verdict.getMessage());
            task.setState(verdict.isSuccess() ? Task.STATE_SUCCESS : Task.STATE_FAIL);
        } catch (Exception e) {
            ErrorClassifier.Verdict verdict = errorClassifier.classify("webservice", e);
            task.setErrorClass(verdict.getErrorClass().name());
            task.setErrorCode(verdict.getCode());
            task.setErrorMessage(verdict.getMessage());
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO,
                    "WebService 调用失败（" + verdict.getErrorClass() + "）：" + e.getMessage()));
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
