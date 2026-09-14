package com.hrh.servicearrange.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.script.ScriptUtil;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.dsl.EdgeDecisionCell;
import com.hrh.servicearrange.dsl.KeyValueDto;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.NodeLoopInfo;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.impl.DataMapOperateExecutor;
import com.hrh.servicearrange.service.ITaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author huangrenhui
 * @date 2022/4/8
 * @flow
 */
@Service(ITaskService.SERVICE_BEAN_NAME)
public class TaskServiceImpl implements ITaskService {
    @Autowired
    private InstDao instDao;
    @Autowired
    private TaskDao taskDao;
    @Autowired
    private DataMapOperateExecutor dataMapOperateExecutor;
    @Autowired
    private InstLogDao instLogDao;

    @Override
    public Boolean edgeDecisionDo(String startId, String instId, String exp, String[] R, String[] L, List<EdgeDecisionCell.ConditionData> conditionData, Task task) {
        Boolean result = false;
        JSONObject pouts = JSONUtil.createObj();
        Set<String> nodeIds = new HashSet<>();
        nodeIds.add(startId);
        String rStr = JSONUtil.toJsonStr(nodeIds);
        String[] id1s = StrUtil.subBetweenAll(rStr, "#pno_", "$");
        if (null != id1s && id1s.length > 0) {
            Stream.of(id1s).forEach(i -> nodeIds.add(i));
        }
        //#header_5dc94b43-fe25-49c8-9ebe-201b08588698$Accept
        String[] id2s = StrUtil.subBetweenAll(rStr, "#header_", "$");
        if (null != id2s && id2s.length > 0) {
            Stream.of(id2s).forEach(i -> nodeIds.add(i));
        }
        Inst inst = instDao.findById(instId).orElse(null);
        List<Task> taskList = taskDao.findAllByInstIdAndNodeIdIn(instId, nodeIds);
        if (null != taskList && taskList.size() > 0) {
            taskList.stream().forEach(t -> {
                String loopTimes = "0";
                //得到循环节点
                List<NodeLoopInfo> nodeLoopInfos = inst.getLoopRunTimesMap().entrySet().stream().filter(e -> e.getValue().getIdsBetweenLoopEdge().contains(t.getNodeId())).map(e -> e.getValue()).collect(Collectors.toList());
                if (null != nodeLoopInfos && nodeLoopInfos.size() > 0) {
                    //获取最近的一次循环线，因为一个节点可能有多个循环线，A-B-C-D-E-F中存在B-E和D-E的循环线，此时应该获取D-E的循环线
                    NodeLoopInfo near = nodeLoopInfos.stream().sorted((n1, n2) -> n1.getIdsBetweenLoopEdge().size() - n2.getIdsBetweenLoopEdge().size()).findFirst().get();
                    loopTimes = near.getLoopEdgeId() + "|" + near.getTimes();
                }
                if (loopTimes.equals(t.getLoopTimes())) {
                    pouts.set(t.getNodeId(), t.getOutputs());
                }
            });
        }
        //处理R表达式
        List<String> R_Values = new ArrayList<>();
        for (String string : R) {
            if (string.contains("#dynamicParams")) {
                String name = string.split("\\$")[1];
                List<KeyValueDto> dynamicParams = inst.getDynamicParams();
                dynamicParams.stream().forEach(map -> {
                    if (name.equals(map.getKey())) {
                        R_Values.add(map.getValue() != null ? map.getValue().toString() : "");
                    }
                });
            } else if ("null".equals(string)) {
                R_Values.add(string);
            } else {
                Object value = dataMapOperateExecutor.valueCompute(startId, string, pouts, task, instLogDao);
                if (value == null) {
                    R_Values.add("null");
                } else {
                    R_Values.add("".equals(value) ? "null" : value.toString());
                }
            }
        }
        for (int i = 0; i < R_Values.size(); i++) {
            if (null != conditionData && conditionData.get(i).getType().trim().toLowerCase().equals("string")) {
                exp = exp.replace("R" + i, "'" + R_Values.get(i) + "'");
            } else {
                exp = exp.replace("R" + i, R_Values.get(i));
            }
        }
        //处理L表达式
        for (int i = 0; i < L.length; i++) {
            if (null != L[i] && "null".equals(L[i])) {
                String[] stringArray = L[i].split("\\;");
                for (String subString : stringArray) {
                    if (subString.contains("#pno_")) {
                        String nodeExpr = subString.split("=")[1].trim();
                        String newNodeId = StrUtil.subBetween(nodeExpr, "#pno_", "$");
                        String loopTimes = "0";
                        List<NodeLoopInfo> nodeLoopInfos = inst.getLoopRunTimesMap().entrySet().stream().filter(e -> e.getValue().getIdsBetweenLoopEdge().contains(newNodeId)).map(e -> e.getValue()).collect(Collectors.toList());
                        if (null != nodeLoopInfos && nodeLoopInfos.size() > 0) {
                            NodeLoopInfo near = nodeLoopInfos.stream().sorted((n1, n2) -> n1.getIdsBetweenLoopEdge().size() - n2.getIdsBetweenLoopEdge().size()).findFirst().get();
                            loopTimes = near.getLoopEdgeId() + "|" + near.getTimes();
                        }
                        Task t = taskDao.findByInstIdAndNodeIdAndLoopTimes(instId, newNodeId, loopTimes);
                        pouts.set(t.getNodeId(), t.getOutputs());
                        Object value = dataMapOperateExecutor.valueCompute(startId, nodeExpr, pouts, task, instLogDao);
                        L[i] = L[i].replace(nodeExpr, value.toString());
                    }
                }
            }
        }
        for (int i = 0; i < L.length; i++) {
            if (null == L[i]) {
                if (null != conditionData && conditionData.get(i).getType().equals("string")) {
                    exp = exp.replace("L" + i, "'null'");
                } else {
                    exp = exp.replace("L" + i, "null");
                }
            } else {
                Object tmp = null;
                try {
                    tmp = ScriptUtil.eval(L[i]);
                } catch (Exception e2) {
                }
                if (null != tmp) {
                    if (null != conditionData && conditionData.get(i).getType().equals("string")) {
                        exp = exp.replace("L" + i, "'" + tmp.toString() + "'");
                    } else {
                        exp = exp.replace("L" + i, tmp.toString());
                    }
                } else {
                    if (null != conditionData && conditionData.get(i).getType().equals("string")) {
                        exp = exp.replace("L" + i, "'" + L[i] + "'");
                    } else {
                        exp = exp.replace("L" + i, L[i]);
                    }
                }
            }
        }
        ExpressionParser parser = new SpelExpressionParser();
        Expression expression = parser.parseExpression(exp);
        result = (Boolean) expression.getValue();

        return result;
    }

    @Override
    public Boolean edgeLoopDecisionDo(String startId, String instId, String exp, String[] r, String[] l, Task task) {
        return edgeDecisionDo(startId, instId, exp, r, l, null, task);
    }
}
