package com.hrh.servicearrange.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
import com.hrh.servicearrange.utils.SafeExpressionEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@Slf4j
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
        if (R == null) {
            R = new String[0];
        }
        if (L == null) {
            L = new String[0];
        }
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
        if (inst == null) {
            return false;
        }
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
            String replacement;
            if (null != conditionData && i < conditionData.size() && conditionData.get(i) != null
                    && conditionData.get(i).getType() != null
                    && "string".equals(conditionData.get(i).getType().trim().toLowerCase())) {
                //字符串字面量：转义单引号，避免值里带 ' 导致表达式语法错误
                replacement = "'" + SafeExpressionEvaluator.escapeSingleQuote(R_Values.get(i)) + "'";
            } else {
                replacement = R_Values.get(i);
            }
            exp = replacePlaceholder(exp, "R" + i, replacement);
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
                        if (t == null) {
                            continue;
                        }
                        pouts.set(t.getNodeId(), t.getOutputs());
                        Object value = dataMapOperateExecutor.valueCompute(startId, nodeExpr, pouts, task, instLogDao);
                        if (value != null) {
                            L[i] = L[i].replace(nodeExpr, value.toString());
                        }
                    }
                }
            }
        }
        for (int i = 0; i < L.length; i++) {
            boolean stringType = null != conditionData && i < conditionData.size() && conditionData.get(i) != null
                    && "string".equals(conditionData.get(i).getType());
            if (null == L[i]) {
                exp = replacePlaceholder(exp, "L" + i, stringType ? "'null'" : "null");
            } else {
                //L 表达式同样只允许受限求值，不再交给 JS 引擎
                Object tmp = SafeExpressionEvaluator.eval(L[i]);
                String literal = tmp == null ? L[i] : tmp.toString();
                exp = replacePlaceholder(exp, "L" + i, stringType
                        ? "'" + SafeExpressionEvaluator.escapeSingleQuote(literal) + "'" : literal);
            }
        }
        if (StrUtil.isEmpty(exp)) {
            return false;
        }
        result = SafeExpressionEvaluator.evalBoolean(exp, false);
        if (!result) {
            log.debug("决策表达式未命中, exp={}", exp);
        }
        return result;
    }

    /**
     * 按占位符边界替换（R1 不能污染 R10），并对替换值做字面量转义
     */
    private String replacePlaceholder(String exp, String placeholder, String value) {
        String safeValue = value == null ? "null" : value;
        return exp.replaceAll("\\b" + placeholder + "\\b",
                java.util.regex.Matcher.quoteReplacement(safeValue));
    }

    @Override
    public Boolean edgeLoopDecisionDo(String startId, String instId, String exp, String[] r, String[] l, Task task) {
        return edgeDecisionDo(startId, instId, exp, r, l, null, task);
    }
}
