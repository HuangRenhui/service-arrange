package com.hrh.servicearrange.executor.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.NodeLoopInfo;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 开始节点，什么都不做，只是赋予执行成功
 */
@Service(value = CellType.START)
public class StartOperateExecutor implements Execute {


    private Task doTaskRun(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        task.setState(Task.STATE_SUCCESS);
        return task;
    }

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        task.setState(Task.STATE_FAIL);
        //mock测试流程
        if (task.getDebug()) {
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

    //获取所有id的数据
    public void getDataByNodeIds(Task task, Inst inst, TaskDao taskDao, String inputs, JSONObject pouts) {
        String[] id1s = StrUtil.subBetweenAll(inputs, "#pno_", "$");
        String[] id2s = StrUtil.subBetweenAll(inputs, "#header_", "$");
        Set<String> nodeIds = Stream.of(id1s).collect(Collectors.toSet());
        nodeIds.addAll(Stream.of(id2s).filter(s -> !StringUtils.isEmpty(s)).collect(Collectors.toSet()));
        List<Task> tasks = taskDao.findAllByInstIdAndNodeIdIn(task.getInstId(), nodeIds);
        if (null != tasks && tasks.size() > 0) {
            tasks.stream().forEach(t -> {
                String loopTimes = "0";
                List<NodeLoopInfo> nodeLoopInfos = inst.getLoopRunTimesMap().entrySet().stream().filter(e -> e.getValue().getIdsBetweenLoopEdge().contains(t.getNodeId())).map(e -> e.getValue()).collect(Collectors.toList());
                if (null != nodeLoopInfos && nodeLoopInfos.size() > 0) {
                    NodeLoopInfo near = nodeLoopInfos.stream().sorted((n1, n2) -> n1.getIdsBetweenLoopEdge().size() - n2.getIdsBetweenLoopEdge().size()).findFirst().get();
                    loopTimes = near.getLoopEdgeId() + "|" + near.getTimes();
                }
                if (loopTimes.equals(t.getLoopTimes())) {
                    pouts.set(t.getNodeId(), t.getOutputs());
                }
            });
        }
    }
}
