package com.hrh.servicearrange.mq;

/**
 * @author huangrenhui
 * @date 2022/3/9
 * @flow
 */

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.config.BackpressureProperties;
import com.hrh.servicearrange.config.OrchestrationProperties;
import com.hrh.servicearrange.convert.TaskInterface;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.dsl.*;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.NodeLoopInfo;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.ErrorClass;
import com.hrh.servicearrange.parser.DslParser;
import com.hrh.servicearrange.parser.annotation.CellType;
import com.hrh.servicearrange.parser.annotation.LoopType;
import com.hrh.servicearrange.parser.annotation.TaskType;
import com.hrh.servicearrange.service.ITaskService;
import com.hrh.servicearrange.utils.JsonSchemaUtil;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务执行完后继续处理下一个节点流程。
 * <p>
 * 不再直接绑定 MQ 通道——MQ 监听在 {@link MqTaskResultListener}（仅在 mq / auto 模式装配），
 * 本地直连由 {@link TaskDispatcher} 调用 {@link #runLocal(String, String, String, Task.Result, Task.RetryRules)}。
 */
@Slf4j
@Component
public class TaskResultConsumer {
    @Autowired
    private TaskProductor taskProductor;

    @Autowired
    private TaskDao taskDao;
    @Autowired
    private InstDao instDao;
    @Autowired
    private InstLogDao instLogDao;

    @Autowired
    private ITaskService taskService;

    @Autowired
    private BackpressureProperties backpressure;
    @Autowired
    private OrchestrationProperties orchestration;

    /**
     * 本地直连入口：进程内直接推进实例状态，不依赖 MQ。
     * <p>
     * 实例串行由 {@link TaskDispatcher} 的 instId 分片线程保证，因此这里不走 ZK 抢锁
     * （本地降级时 ZooKeeper 也可能同时不可用）。
     */
    public void runLocal(String id, String instId, String state, Task.Result outputs, Task.RetryRules retryRules) {
        run(id, instId, state, outputs, retryRules, null, null, null);
    }

    /**
     * @param id          任务id
     * @param instId      实例id
     * @param state       任务状态
     * @param outputs     任务输出
     * @param retryRules  节点重试规则
     * @param channel     mq通信通道
     * @param deliveryTag
     * @param lockNode
     */
    public void run(String id, String instId, String state, Task.Result outputs, Task.RetryRules retryRules, Channel channel, Long deliveryTag, String lockNode) {
        if (StringUtils.isEmpty(id) || StringUtils.isEmpty(instId) || StringUtils.isEmpty(state)) {
            taskProductor.ackTask(instId, deliveryTag, channel,
                    "id/instId/state can't be null:id=" + id + ",instId=" + instId + ",state=" + state, lockNode);
            return;
        }
        //下一个运行节点集合
        List<Task> nextTasks = new ArrayList<>();
        //找到当前任务
        Task task = taskDao.findById(id).orElse(null);
        if (task == null) {
            taskProductor.ackTask(instId, deliveryTag, channel, "do ack! task not find:" + id, lockNode);
            return;
        }
        Inst inst = instDao.findById(instId).orElse(null);
        if (inst == null) {
            taskProductor.ackTask(instId, deliveryTag, channel, "do ack! inst not find:" + instId, lockNode);
            return;
        } else if (Inst.STATE_SUSPEND.equals(inst.getState())) {
            inst.getSuspendNodes().add(task.getNodeName() + "|" + task.getLoopTimes());
            instDao.save(inst);
            taskProductor.ackTask(instId, deliveryTag, channel, "do ack! inst is suspend:" + instId, lockNode);
            return;
        } else if (Inst.STATE_CANCEL.equals(inst.getState())) {
            taskProductor.ackTask(instId, deliveryTag, channel, "do ack! inst is cancel:" + instId, lockNode);
            return;
        }
        //重试次数超过上限，强制 ack 丢弃，避免毒消息永久占用消费线程
        if (task.getStateNackTimes() != null && task.getStateNackTimes() >= backpressure.getMaxHandleTimes()) {
            taskProductor.ackTask(instId, deliveryTag, channel, "stateNackTimes>=" + backpressure.getMaxHandleTimes() + ",this message may never be ack,force ack this message", lockNode);
            return;
        }
        task.setEndDate(new Date());
        //错误分类：FATAL 直接跳过重试（进入补偿/失败分支）；为 null 表示旧数据或未分类，按可重试保持原行为
        boolean retryable = task.getErrorClass() == null
                || ErrorClass.RETRYABLE.name().equals(task.getErrorClass());
        if (state.equals(Task.STATE_FAIL) && !retryable) {
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(),
                    InstLog.LEVEL_WARN, "错误分类 FATAL（code=" + task.getErrorCode() + "），跳过重试直接失败"));
        }
        if (state.equals(Task.STATE_FAIL) && retryable && canRetry(retryRules)) {
            int done = retryRules.getDoneTimes() == null ? 0 : retryRules.getDoneTimes();
            retryRules.setDoneTimes(done + 1);
            task.setRetryRules(retryRules);
            task.setState(Task.STATE_WAITE);
            task.setStartDate(new Date());
            taskDao.save(task);
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(),
                    InstLog.LEVEL_WARN, "任务失败，第 " + retryRules.getDoneTimes() + " 次重试"));
            sleepQuietly(retryDelayMs(retryRules));
            taskProductor.sendTaskRun(task);
            taskProductor.ackTask(instId, deliveryTag, channel, "do retry node:" + task.getNodeId(), lockNode);
            return;
        }
        if (state.equals(Task.STATE_SKIP)) {
            inst.getSkipNodes().add(task.getNodeId());
        } else if (state.equals(Task.STATE_SUCCESS)) {
            inst.getSuccessNodes().add(task.getNodeId());
        } else if (state.equals(Task.STATE_FAIL)) {
            inst.getFailNodes().add(task.getNodeId());
            inst.setState(Inst.STATE_FAIL);
            inst.setFailReason((task.getNodeName() == null ? task.getNodeId() : task.getNodeName()) + " failed");
            inst.setEndDate(new Date());
        }
        instDao.save(inst);
        task.setRetryRules(retryRules);
        task.setState(state);
        task.setOutputs(outputs);
        taskDao.save(task);
        //处理节点的线和下一节点
        try {
            //节点下一个连接线:线source、线id
            Set<String> outEdges = inst.getNodeOutEdgesMap().get(task.getNodeId());
            //所有节点入度线：线target、线id
            Map<String, Set<String>> nodeInputEdgesMap = inst.getNodeInputEdgesMap();
            //子父节点关系
            Map<String, Set<String>> nodeParentsMap = inst.getNodeParentsMap();
            //父子节点关系
            Map<String, Set<String>> nodeChildsMap = inst.getNodeChildsMap();
            //所有节点
            Map<String, Cell> nodeMap = inst.getNodeMap();
            Set<String> successNodes = inst.getSuccessNodes();
            Set<String> skipNodes = inst.getSkipNodes();
            Set<String> roots = inst.getRoots();
            Set<String> failNodes = inst.getFailNodes();
            Set<String> waitingNodes = inst.getWaitingNodes();
            //获取强组合、循环线
            List<Cell> compensateEdges = null;
            List<Cell> outLoopEdges = null;
            if (outEdges != null && outEdges.size() > 0) {
                compensateEdges = outEdges.stream().map(nodeMap::get).filter(c -> c != null && CellType.EDGE_COMPENSATE.equals(c.getCellType())).collect(Collectors.toList());
                outLoopEdges = outEdges.stream().map(nodeMap::get).filter(c -> c != null && CellType.EDGE_LOOP.equals(c.getCellType())).collect(Collectors.toList());
            }
            //任务失败，进行强组合节点回退
            if (Task.STATE_FAIL.equals(state) && compensateEdges != null && compensateEdges.size() > 0
                    && compensateEdges.get(0).getTarget() != null) {
                String target = compensateEdges.get(0).getTarget().getCell();
                Cell cell = nodeMap.get(target);
                Task t = convertCell(cell, inst);
                if (t != null) {
                    t.setStartDate(new Date());
                    taskDao.deleteByInstIdAndNodeIdAndLoopTimes(instId, t.getNodeId(), t.getLoopTimes());
                    taskDao.save(t);
                    instDao.save(inst);
                    taskProductor.sendTaskRun(t);
                }
                taskProductor.ackTask(instId, deliveryTag, channel, "do compensate -> node:", lockNode);
                return;
            }
            //任务跳过或成功，进行下一节点运行
            if (Task.STATE_SKIP.equals(state) || Task.STATE_SUCCESS.equals(state)) {
                final List<String> finalNextIds = new ArrayList<>();
                if (Task.STATE_SUCCESS.equals(state)) {
                    //循环线处理，doWhile，先执行节点再判断条件
                    if (outLoopEdges != null && outLoopEdges.size() > 0) {
                        EdgeLoopCell elc = JSONUtil.toBean(JSONUtil.parseObj(outLoopEdges.get(0)), EdgeLoopCell.class);
                        if (elc.getData() != null) {
                        String exp = elc.getData().getJsonpathElexpression();
                        String[] R = elc.getData().getR();
                        String[] L = elc.getData().getL();
                        EdgeLoopCell.CycleInfo cycleInfo = elc.getData().getCycleInfo();
                        if (cycleInfo != null && cycleInfo.getRule() != null) {
                            applyCycleStep(inst, cycleInfo);
                            String startId = firstRoot(roots);
                            Boolean flag = taskService.edgeLoopDecisionDo(startId, instId, exp, R, L, task);
                            Set<String> cellIdsBetweenLoopEdge = DslParser.getCellIdsBetweenLoopEdge(inst, outLoopEdges.get(0));
                            if (Boolean.TRUE.equals(flag) && bumpLoopTimes(inst, outLoopEdges.get(0).getId(), task)) {
                                inst.getWaitingNodes().addAll(cellIdsBetweenLoopEdge);
                                inst.getSuccessNodes().removeAll(cellIdsBetweenLoopEdge);
                                successNodes.removeAll(cellIdsBetweenLoopEdge);
                                inst.getFailNodes().removeAll(cellIdsBetweenLoopEdge);
                                inst.getSkipNodes().removeAll(cellIdsBetweenLoopEdge);
                                instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO, "本节点的输出循环线表达式成立，将循环线中间的节点设定为未执行：" + JSONUtil.toJsonStr(cellIdsBetweenLoopEdge)));
                                String target = elc.getTarget() == null ? null : elc.getTarget().getCell();
                                Cell cell = target == null ? null : inst.getNodeMap().get(target);
                                Task next = convertCell(cell, inst);
                                if (next != null) {
                                    next.setStartDate(new Date());
                                    taskDao.deleteByInstIdAndNodeIdAndLoopTimes(instId, next.getNodeId(), next.getLoopTimes());
                                    taskDao.save(next);
                                    taskProductor.sendTaskRun(next);
                                }
                                instDao.save(inst);
                                taskProductor.ackTask(instId, deliveryTag, channel, "enter loop, do ack.", lockNode);
                                return;
                            } else {
                                inst.getWaitingNodes().removeAll(cellIdsBetweenLoopEdge);
                                waitingNodes.removeAll(cellIdsBetweenLoopEdge);
                                successNodes.addAll(cellIdsBetweenLoopEdge);
                            }
                        }
                        }
                    }
                }
                //实例输出
                if (task.getType().equals(CellType.END)) {
                    inst.setEndDate(new Date());
                    if (Task.STATE_SUCCESS.equals(state)) {
                        inst.setState(Inst.STATE_SUCCESS);
                        inst.setOutputs(task.getOutputs());
                    } else {
                        inst.setState(Inst.STATE_FAIL);
                    }
                    instDao.save(inst);
                    taskProductor.ackTask(instId, deliveryTag, channel, "end node finished, do ack.", lockNode);
                    return;
                }
                Set<String> childIds = nodeChildsMap.get(task.getNodeId());
                if (childIds != null) {
                    childIds.forEach(finalNextIds::add);
                }
                List<String> whileDoNextIds = new ArrayList<>();
                //判断后面节点是否有决策线，类型是whileDo，表示先判断条件再执行节点，且决策线不需要进行循环运行，那么当前节点就需要去除掉不运行了
                //所以当前遍历需要从尾遍历，里面有remove操作
                for (int j = finalNextIds.size() - 1; j >= 0; j--) {
                    String ii = finalNextIds.get(j);
                    //入度线，先进行whileDo线判断
                    Set<String> iiInputEdges = nodeInputEdgesMap.get(ii);
                    if (null != iiInputEdges && iiInputEdges.size() > 0) {
                        //获取whileDo的线
                        List<Cell> inLoopWhileDoEdges = iiInputEdges.stream().map(nodeMap::get).filter(c -> {
                            if (c == null || !CellType.EDGE_LOOP.equals(c.getCellType())) {
                                return false;
                            }
                            EdgeLoopCell.Data data = JSONUtil.toBean(JSONUtil.toJsonStr(c.getData(), JsonSchemaUtil.jsonConfig), EdgeLoopCell.Data.class);
                            return data != null && data.getCycleInfo() != null && LoopType.WHILE_DO.equals(data.getCycleInfo().getType());
                        }).collect(Collectors.toList());
                        if (null != inLoopWhileDoEdges && inLoopWhileDoEdges.size() > 0) {
                            EdgeLoopCell elc = JSONUtil.toBean(JSONUtil.parseObj(inLoopWhileDoEdges.get(0)), EdgeLoopCell.class);
                            if (elc.getData() == null) {
                                continue;
                            }
                            String exp = elc.getData().getJsonpathElexpression();
                            String[] R = elc.getData().getR();
                            String[] L = elc.getData().getL();
                            String startId = firstRoot(roots);
                            EdgeLoopCell.CycleInfo cycleInfo = elc.getData().getCycleInfo();
                            if (cycleInfo != null && cycleInfo.getRule() != null) {
                                applyCycleStep(inst, cycleInfo);
                            }
                            Boolean whileDo = taskService.edgeLoopDecisionDo(startId, instId, exp, R, L, task);
                            if (!Boolean.TRUE.equals(whileDo)) {
                                Set<String> cellIdsBetweenLoopEdge = DslParser.getCellIdsBetweenLoopEdge(inst, inLoopWhileDoEdges.get(0));
                                inst.getWaitingNodes().removeAll(cellIdsBetweenLoopEdge);
                                inst.getSkipNodes().removeAll(cellIdsBetweenLoopEdge);
                                finalNextIds.remove(j);
                                Set<String> loopSourceChildren = nodeChildsMap.get(inLoopWhileDoEdges.get(0).getSource() == null ? null : inLoopWhileDoEdges.get(0).getSource().getCell());
                                if (loopSourceChildren != null) {
                                    loopSourceChildren.forEach(whileDoNextIds::add);
                                }
                            } else {
                                bumpLoopTimes(inst, inLoopWhileDoEdges.get(0).getId(), task);
                            }
                        }
                    }
                }
                finalNextIds.addAll(whileDoNextIds);
                //转换为task
                List<String> nextIds = finalNextIds.stream().distinct().collect(Collectors.toList());
                for (int j = 0; j < nextIds.size(); j++) {
                    //获取下一节点
                    String ii = nextIds.get(j);
                    //获取下一节点父节点
                    Set<String> pids = nodeParentsMap.get(ii);
                    if (pids == null) {
                        pids = Collections.emptySet();
                    }
                    Set<String> iiInputEdges = nodeInputEdgesMap.get(ii);
                    if (null != iiInputEdges && iiInputEdges.size() > 0) {
                        final Set<String> parentFilter = pids;
                        pids = parentFilter.stream().filter(pi -> {
                            List<String> notLoopInPids = iiInputEdges.stream()
                                    .map(nodeMap::get)
                                    .filter(edge -> edge != null && !CellType.EDGE_LOOP.equals(edge.getCellType()) && edge.getSource() != null)
                                    .map(edge -> edge.getSource().getCell())
                                    .collect(Collectors.toList());
                            return notLoopInPids.contains(pi);
                        }).collect(Collectors.toSet());
                    }
                    long psuccessNum = pids.stream().filter(q -> successNodes.contains(q) || skipNodes.contains(q)).count();
                    if (psuccessNum == pids.size()) {
                        Cell cell = nodeMap.get(ii);
                        Task t = convertCell(cell, inst);
                        if (t != null) {
                            t.setStartDate(new Date());
                            taskDao.deleteByInstIdAndNodeIdAndLoopTimes(instId, t.getNodeId(), t.getLoopTimes());
                            taskDao.save(t);
                            nextTasks.removeIf(tf -> tf.getNodeId().equals(ii));
                            nextTasks.add(t);
                        } else if (cell != null) {
                            inst.setState(Inst.STATE_FAIL);
                            instDao.save(inst);
                        }
                    }
                }
            }
            //run task
            for (int i = 0; i < nextTasks.size(); i++) {
                Task t = nextTasks.get(i);
                Set<String> inputEdges = inst.getNodeInputEdgesMap().get(t.getNodeId());
                List<Cell> dicisionEdges = Collections.emptyList();
                if (null != inputEdges && inputEdges.size() > 0) {
                    dicisionEdges = inputEdges.stream().map(nodeMap::get)
                            .filter(cell -> cell != null && CellType.EDGE_DECISION.equals(cell.getCellType()))
                            .collect(Collectors.toList());
                }
                Set<String> pids = inst.getNodeParentsMap().get(t.getNodeId());
                if (pids == null) {
                    pids = Collections.emptySet();
                }
                long pSkipNum = pids.stream().filter(p -> skipNodes.contains(p)).count();
                if (!pids.isEmpty() && pSkipNum == pids.size()) {
                    instDao.save(inst);
                    t.setState(Task.STATE_SKIP);
                    taskProductor.sendTaskResult(t);
                    instLogDao.save(new InstLog(instId, t.getPlanId(), t.getNodeId(), t.getId(), InstLog.LEVEL_INFO, "节点的所有父级状态皆为SKIP，此节点也进行SKIP!"));
                } else {
                    //决策线处理
                    if (null != dicisionEdges && dicisionEdges.size() > 0) {
                        long trueCount = dicisionEdges.stream().filter(e -> {
                            try {
                                EdgeDecisionCell edgeDecisionCell = JSONUtil.toBean(JSONUtil.parseObj(e), EdgeDecisionCell.class);
                                if (edgeDecisionCell.getData() == null) {
                                    return false;
                                }
                                String exp = edgeDecisionCell.getData().getJsonpathElexpression();
                                String[] R = edgeDecisionCell.getData().getR();
                                String[] L = edgeDecisionCell.getData().getL();
                                List<EdgeDecisionCell.ConditionData> conditionData = edgeDecisionCell.getData().getConditionData();
                                String startId = firstRoot(roots);
                                return Boolean.TRUE.equals(taskService.edgeDecisionDo(startId, instId, exp, R, L, conditionData, t));
                            } catch (Exception ex) {
                                instLogDao.save(new InstLog(instId, t.getPlanId(), t.getNodeId(), t.getId(), InstLog.LEVEL_ERRO, "决策线求值失败：" + ex.getMessage()));
                                return false;
                            }
                        }).count();
                        //节点所有决策线都通过了，执行节点
                        if (trueCount == dicisionEdges.size()) {
                            instDao.save(inst);
                            taskProductor.sendTaskRun(t);
                        } else {
                            //决策不通过，节点跳过不运行
                            instDao.save(inst);
                            t.setState(Task.STATE_SKIP);
                            taskProductor.sendTaskResult(t);
                        }
                    } else {
                        instDao.save(inst);
                        taskProductor.sendTaskRun(t);
                    }
                }
                inst.getWaitingNodes().remove(t.getNodeId());
                //所有线节点都跳过
                if(null!=inputEdges&&inputEdges.size()>0){
                    inst.getWaitingNodes().removeAll(inputEdges);
                    inst.getSkipNodes().addAll(inputEdges);
                }
            }
            //节点从等待状态移除
            inst.getWaitingNodes().remove(task.getNodeId());
            taskProductor.ackTask(instId, deliveryTag, channel, "send next task success, do ack.", lockNode);
        } catch (Exception e) {
            log.error("send next task fail, instId={}, taskId={}", instId,
                    task == null ? null : task.getId(), e);
            inst.setState(Inst.STATE_FAIL);
            instDao.save(inst);
            taskProductor.nAckTask(instId, deliveryTag, channel, "before send next task fail, do nack.", lockNode);
        } finally {
            log.debug("send next task finished, instId={}, taskId={}", inst.getId(),
                    task == null ? null : task.getId());
        }
    }

    private Task convertCell(Cell cell, Inst inst) {
        if (cell == null) {
            return null;
        }
        String resolved = com.hrh.servicearrange.parser.CellTypeMapper.resolve(cell);
        try {
            TaskInterface ti = SpringUtil.getBean(resolved + TaskType.suffix);
            return ti.convert(cell, inst);
        } catch (Exception first) {
            try {
                TaskInterface fallback = SpringUtil.getBean(CellType.NODE_OP + TaskType.suffix);
                return fallback.convert(cell, inst);
            } catch (Exception e) {
                instLogDao.save(new InstLog(inst.getId(), inst.getPlanId(), cell.getId(), null, InstLog.LEVEL_ERRO,
                        "找不到节点转换器 " + resolved + TaskType.suffix + "：" + first.getMessage()));
                return null;
            }
        }
    }

    private boolean bumpLoopTimes(Inst inst, String loopEdgeId, Task task) {
        if (StrUtil.isEmpty(loopEdgeId) || inst.getLoopRunTimesMap() == null) {
            return false;
        }
        NodeLoopInfo info = inst.getLoopRunTimesMap().get(loopEdgeId);
        if (info == null) {
            return false;
        }
        int times = info.getTimes() == null ? 0 : info.getTimes();
        if (times >= backpressure.getMaxLoopTimes()) {
            instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO,
                    "循环次数超过上限 " + backpressure.getMaxLoopTimes() + "，退出循环"));
            return false;
        }
        info.setTimes(times + 1);
        inst.getLoopRunTimesMap().put(loopEdgeId, info);
        return true;
    }

    private void applyCycleStep(Inst inst, EdgeLoopCell.CycleInfo cycleInfo) {
        if (cycleInfo == null || inst.getDynamicParams() == null) {
            return;
        }
        String select = cycleInfo.getJsonpathSelect();
        if (StrUtil.isEmpty(select) || !select.contains("$")) {
            return;
        }
        String key = select.substring(select.indexOf('$') + 1);
        List<KeyValueDto> dynamicParams = inst.getDynamicParams();
        int idx = -1;
        for (int i = 0; i < dynamicParams.size(); i++) {
            if (key.equals(dynamicParams.get(i).getKey())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return;
        }
        Object dynamicValue = dynamicParams.get(idx).getValue();
        String expressionCaculate = String.valueOf(dynamicValue) + cycleInfo.getRule() + cycleInfo.getStepLength();
        try {
            ExpressionParser parser = new SpelExpressionParser();
            Expression expression = parser.parseExpression(expressionCaculate);
            Object value = expression.getValue(org.springframework.expression.spel.support.SimpleEvaluationContext.forReadOnlyDataBinding().build());
            dynamicParams.get(idx).setValue(value);
            inst.setDynamicParams(dynamicParams);
            instDao.save(inst);
        } catch (Exception e) {
            instLogDao.save(new InstLog(inst.getId(), inst.getPlanId(), null, null, InstLog.LEVEL_ERRO, "循环计数求值失败：" + e.getMessage()));
        }
    }

    private String firstRoot(Set<String> roots) {
        if (roots == null || roots.isEmpty()) {
            return null;
        }
        return roots.iterator().next();
    }

    private boolean canRetry(Task.RetryRules retryRules) {
        if (retryRules == null || retryRules.getTimes() == null || retryRules.getTimes() <= 0) {
            return false;
        }
        int done = retryRules.getDoneTimes() == null ? 0 : retryRules.getDoneTimes();
        return done < retryRules.getTimes();
    }

    private long retryDelayMs(Task.RetryRules retryRules) {
        long delay = 0;
        if (retryRules.getDelaySecondTimes() != null && retryRules.getDelaySecondTimes() > 0) {
            delay = retryRules.getDelaySecondTimes() * 1000L;
        }
        long cap = orchestration == null ? 3000L : orchestration.getRetryDelayMaxMs();
        return Math.min(delay, cap);
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
