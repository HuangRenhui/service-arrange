package com.hrh.servicearrange.convert;

import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dsl.Cell;
import com.hrh.servicearrange.dsl.StartCell;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.parser.annotation.TaskType;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * @author huangrenhui
 * @date 2022/4/7
 * @flow 将dsl开始节点信息转换为对应任务
 */
@Service(TaskType.START)
public class StartTask implements TaskInterface {
    @Override
    public Task process(Cell cell, Inst inst, Task task) {
        StartCell startCell = JSONUtil.toBean(JSONUtil.parseObj(cell), StartCell.class);
        task.setInputs(inst.getDslInputParams());
        task.getOutputs().setContentType("application/json");
        task.getOutputs().setJsonSchema(startCell.getData().getInputsJsonSchema());
        task.getOutputs().setValue(inst.getDslInputParams());
        task.getOutputs().setHeaderParams(inst.getHeaderParams());
        task.setState(Task.STATE_SUCCESS);
        Date date = new Date();
        task.setStartDate(date);
        task.setEndDate(date);
        task.setExecutorStartDate(date);
        task.setExecutorEndDate(date);
        return task;
    }
}
