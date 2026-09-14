package com.hrh.servicearrange.convert;

import com.hrh.servicearrange.dsl.Cell;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.NodeLoopInfo;
import com.hrh.servicearrange.entity.Task;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author huangrenhui
 * @date 2022/4/7
 * @flow 将节点转换为对应任务
 */
public interface TaskInterface {
    default Task convert(Cell cell, Inst inst) {
        Task task = new Task();
        if (!StringUtils.isEmpty(cell.getName())) {
            task.setNodeName(cell.getName());
        } else {
            task.setNodeName(cell.getCellType() + "_" + cell.getId());
        }
        task.setInstId(inst.getId());
        task.setPlanId(inst.getPlanId());
        task.setNodeId(cell.getId());
        Date date = new Date();
        task.setCreateDate(date);
        task.setModifyDate(date);
        task.setType(cell.getCellType());
        task.setState(Task.STATE_WAITE);
        //获取循环线之间的节点信息：循环线id、循环线间节点集合、循环次数
        List<NodeLoopInfo> nodeLoopInfos = inst.getLoopRunTimesMap().entrySet().stream().filter(e -> e.getValue().getIdsBetweenLoopEdge().contains(cell.getId())).map(e -> e.getValue()).collect(Collectors.toList());
        if (nodeLoopInfos != null && nodeLoopInfos.size() > 0) {
            //获取最近的循环线
            NodeLoopInfo near = nodeLoopInfos.stream().sorted((n1, n2) -> n1.getIdsBetweenLoopEdge().size() - n2.getIdsBetweenLoopEdge().size()).findFirst().get();
            task.setLoopTimes(near.getLoopEdgeId() + "|" + near.getTimes());
        } else {
            task.setLoopTimes("0");
        }
        task = this.process(cell, inst, task);
        return task;

    }

    /**
     * 只需转换task的入参即可，task的基础信息已经进行了转换{@link TaskInterface#convert(Cell, Inst)}
     *
     * @param cell
     * @param inst
     * @param task
     * @return
     */
    Task process(Cell cell, Inst inst, Task task);
}
