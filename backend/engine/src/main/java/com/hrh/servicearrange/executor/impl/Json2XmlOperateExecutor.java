package com.hrh.servicearrange.executor.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.XML;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service(CellType.FUN_JSON2XML)
public class Json2XmlOperateExecutor implements Execute {
    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        return XmlConvertSupport.convert(task, instLogDao, true);
    }
}

@Service(CellType.FUN_XML2JSON)
class Xml2JsonOperateExecutor implements Execute {
    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        return XmlConvertSupport.convert(task, instLogDao, false);
    }
}

final class XmlConvertSupport {
    private XmlConvertSupport() {
    }

    static Task convert(Task task, InstLogDao instLogDao, boolean jsonToXml) {
        try {
            JSONObject inputs = JSONUtil.parseObj(task.getInputs());
            String payload = inputs.getStr("value");
            if (StringUtils.isEmpty(payload)) {
                payload = inputs.getStr("script");
            }
            if (StringUtils.isEmpty(payload)) {
                payload = task.getInputs();
            }
            String out;
            if (jsonToXml) {
                JSONObject obj = JSONUtil.isJsonObj(payload) ? JSONUtil.parseObj(payload) : inputs;
                out = XML.toXml(obj);
                task.getOutputs().setContentType("application/xml");
            } else {
                JSONObject obj = XML.toJSONObject(payload);
                out = JSONUtil.toJsonStr(obj);
                task.getOutputs().setContentType("application/json");
            }
            task.getOutputs().setValue(out);
            task.setState(Task.STATE_SUCCESS);
        } catch (Exception e) {
            task.setState(Task.STATE_FAIL);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, "XML/JSON 转换失败：" + e.getMessage()));
        }
        return task;
    }
}
