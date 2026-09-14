package com.hrh.servicearrange.executor.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.dsl.EndCell;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author huangrenhui
 * @date 2022/5/9
 * @flow 结束节点，将上一个节点数据根据输出规则进行调整，结果作为整个实例的结果
 */
@Service(CellType.END)
public class EndOperateExecutor implements Execute {
    @Autowired
    private DataMapOperateExecutor dataMapOperateExecutor;
    @Autowired
    private StartOperateExecutor startOperateExecutor;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {

        task.setState(Task.STATE_FAIL);
        if (task.getDebug()) { //调试时  end节点可以忽略这些失败信息，默认成功
            try {
                task = doTaskRun(task, inst, taskDao, instLogDao);
            } catch (Exception e) {
                instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO, "最终结果调试时，参数转换异常，请确认模型出参的所有映射相关的节点是否都参与了调试！" + e.getMessage()));
                e.printStackTrace();
            } finally {
                task.setState(Task.STATE_SUCCESS);
                task.setExecutorEndDate(new Date());
            }
        } else {
            task = doTaskRun(task, inst, taskDao, instLogDao);
        }
        return task;
    }

    private Task doTaskRun(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        //获取节点的输入
        String inputs = task.getInputs();
        JSONConfig jsonConfig = JSONConfig.create().setIgnoreNullValue(false);
        JSONObject pouts = JSONUtil.createObj(jsonConfig);
        if (!StringUtils.isEmpty(inputs)) {
            Task startTask = taskDao.findStartTask(task.getInstId(), CellType.START);
            pouts.set(startTask.getNodeId(), startTask.getOutputs());
            //获取所有id的数据
            startOperateExecutor.getDataByNodeIds(task, inst, taskDao, inputs, pouts);
            EndCell.Data data = JSONUtil.toBean(JSONUtil.parseObj(inputs), EndCell.Data.class);
            //必须要知道输出类型格式：application/json、其他
            if (!StringUtils.isEmpty(data.getContentType())) {
                if (data.getContentType().equals("application/json")) {
                    String jsonSchema = data.getOutputsJsonSchema();
                    if (!JSONUtil.isJson(jsonSchema)) {
                        task.setState(Task.STATE_FAIL);
                        instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO, "未设置模型的出参/出参格式不正确！运行失败！"));
                        return task;
                    }
                    JSONObject jsonSchemaObj = JSONUtil.parseObj(jsonSchema);
                    if (jsonSchemaObj.containsKey("jsonpathMapping")) {
                        String jsonpathMapping = jsonSchemaObj.getStr("jsonpathMapping");
                        Object value = dataMapOperateExecutor.valueCompute(startTask.getNodeId(), jsonpathMapping, pouts, task, instLogDao);
                        task.getOutputs().setValue(JSONUtil.toJsonStr(value, jsonConfig));
                    } else {
                        //对象
                        JSONObject valueJsonObj = JSONUtil.createObj(jsonConfig);
                        JSONObject properties = jsonSchemaObj.getJSONObject("properties");
                        properties.keySet().stream().forEach(key -> {
                            JSONObject obj = properties.getJSONObject(key);
                            valueJsonObj.set(key, null);
                            if (obj.containsKey("jsonpathMapping") && !StringUtils.isEmpty(obj.getStr("jsonpathMapping"))) {
                                String jsonpathMapping = obj.getStr("jsonpathMapping");
                                Object value = dataMapOperateExecutor.valueCompute(startTask.getNodeId(), jsonpathMapping, pouts, task, instLogDao);
                                obj.set("value", value);
                                properties.set(key, obj);
                                valueJsonObj.set(key, value);
                            }
                        });
                        jsonSchemaObj.set("properties", properties);
                        task.getOutputs().setValue(JSONUtil.toJsonStr(valueJsonObj, jsonConfig));
                    }
                } else {
                    //非application/json格式
                    String valueJsonpathMapping = data.getValueJsonpathMapping();
                    String[] id3s = StrUtil.subBetweenAll(valueJsonpathMapping, "#pno_", "$");
                    String[] id4s = StrUtil.subBetweenAll(valueJsonpathMapping, "#header_", "$");
                    Set<String> nodeIds2 = Stream.of(id3s).collect(Collectors.toSet());
                    nodeIds2.addAll(Stream.of(id4s).filter(s -> !StringUtils.isEmpty(s)).collect(Collectors.toSet()));
                    List<Task> tasks2 = (List<Task>) taskDao.findAllByInstIdAndNodeIdIn(task.getInstId(), nodeIds2);
                    if (null != tasks2 && tasks2.size() > 0) {
                        tasks2.stream().forEach(t -> pouts.set(t.getNodeId(), t.getOutputs()));
                    }
                    Object value = dataMapOperateExecutor.valueCompute(startTask.getNodeId(), valueJsonpathMapping, pouts, task, instLogDao);
                    task.getOutputs().setValue(JSONUtil.toJsonStr(value, jsonConfig));
                }
            }
        }
        task.setState(Task.STATE_SUCCESS);

        return task;
    }


}
