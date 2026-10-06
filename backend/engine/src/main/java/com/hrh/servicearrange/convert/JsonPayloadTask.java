package com.hrh.servicearrange.convert;

import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dsl.Cell;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.parser.CellTypeMapper;

/**
 * 通用转换：把节点 data 写入 Task.inputs，并把 type 归一成引擎 cellType。
 */
public class JsonPayloadTask implements TaskInterface {

    @Override
    public Task process(Cell cell, Inst inst, Task task) {
        String resolved = CellTypeMapper.resolve(cell);
        if (resolved != null) {
            task.setType(resolved);
        }
        Object data = cell.getData();
        task.setInputs(data == null ? "{}" : JSONUtil.toJsonStr(data));
        return task;
    }
}
