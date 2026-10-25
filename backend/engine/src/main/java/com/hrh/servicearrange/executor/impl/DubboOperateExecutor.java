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

@Service(CellType.OPERATOR_DUBBO)
public class DubboOperateExecutor implements Execute {

    @Value("${task.http.read-timeout:30000}")
    private int readTimeout;

    /**
     * 错误码分类器：把网关状态码 / 泛化错误码映射为 SUCCESS / RETRYABLE / FATAL
     */
    @Autowired
    private ErrorClassifier errorClassifier;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        try {
            JSONObject inputs = JSONUtil.parseObj(task.getInputs());
            String url = inputs.getStr("url", inputs.getStr("serviceUrl"));
            if (!StringUtils.isEmpty(url) && url.startsWith("http")) {
                HttpResponse resp = HttpRequest.post(url).body(task.getInputs()).timeout(readTimeout).execute();
                String body = resp.body();
                task.getOutputs().setContentType("application/json");
                task.getOutputs().setValue(body);
                //按 task.error-mapping.dubbo 判定：默认 2xx 成功，可配 body-code 识别泛化错误码
                ErrorClassifier.Verdict verdict = errorClassifier.classify("dubbo", resp.getStatus(), body, null);
                task.setErrorClass(verdict.getErrorClass().name());
                task.setErrorCode(verdict.getCode());
                task.setErrorMessage(verdict.getMessage());
                task.setState(verdict.isSuccess() ? Task.STATE_SUCCESS : Task.STATE_FAIL);
                return task;
            }
            //配置缺失属于不可恢复的错误，直接判 FATAL，避免无意义重试
            task.setErrorClass(ErrorClass.FATAL.name());
            task.setErrorCode("URL_MISSING");
            task.setErrorMessage("Dubbo 泛化调用未配置 HTTP 网关地址");
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO,
                    "Dubbo 泛化调用未配置 HTTP 网关地址（url/serviceUrl），请填写可调用的 HTTP 入口"));
        } catch (Exception e) {
            ErrorClassifier.Verdict verdict = errorClassifier.classify("dubbo", e);
            task.setErrorClass(verdict.getErrorClass().name());
            task.setErrorCode(verdict.getCode());
            task.setErrorMessage(verdict.getMessage());
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO,
                    "Dubbo 节点失败（" + verdict.getErrorClass() + "）：" + e.getMessage()));
        }
        return task;
    }
}
