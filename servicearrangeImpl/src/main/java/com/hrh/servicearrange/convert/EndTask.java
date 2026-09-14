package com.hrh.servicearrange.convert;

import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dsl.Cell;
import com.hrh.servicearrange.dsl.EndCell;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.parser.annotation.TaskType;
import org.springframework.stereotype.Service;

/**
 * 结束节点
 */
@Service(TaskType.END)
public class EndTask implements TaskInterface {

    @Override
    public Task process(Cell cell, Inst inst, Task task) {
        EndCell tCell = JSONUtil.toBean(JSONUtil.parseObj(cell), EndCell.class);
        EndCell.Data data = tCell.getData();
        task.setInputs(JSONUtil.toJsonStr(data));
        task.getOutputs().setContentType(data.getContentType());
        task.getOutputs().setHeaderParams(inst.getHeaderParams());
        task.getOutputs().setJsonSchema(data.getOutputsJsonSchema());

        return task;
    }

}
