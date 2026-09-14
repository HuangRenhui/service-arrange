package com.hrh.servicearrange.mq;

/**
 * @author huangrenhui
 * @date 2022/3/9
 * @flow
 */

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.convert.TaskInterface;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.dsl.*;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.NodeLoopInfo;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.parser.DslParser;
import com.hrh.servicearrange.parser.annotation.CellType;
import com.hrh.servicearrange.parser.annotation.LoopType;
import com.hrh.servicearrange.parser.annotation.TaskType;
import com.hrh.servicearrange.service.ITaskService;
import com.hrh.servicearrange.utils.JsonSchemaUtil;
import com.rabbitmq.client.Channel;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务执行完后继续处理下一个节点流程
 */
@Component
@EnableBinding(MqChannelProcessor.class)
public class TaskResultConsumer {
    @Autowired
    private TaskProductor taskProductor;

    @Autowired
    CuratorFramework curatorFramework;
    @Autowired
    private TaskDao taskDao;
    @Autowired
    private InstDao instDao;
    @Autowired
    private InstLogDao instLogDao;

    @Autowired
    private ITaskService taskService;

    @StreamListener(target = MqChannelProcessor.TASK_RESULT_INPUT)
    @RabbitHandler
    public void listenInstTaskResult(Message<?> message,
                                     @Header(AmqpHeaders.CHANNEL) Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) {
        System.out.println("【task.result_queue_name】receive:" + message.toString());
        JSONObject jsonObject = JSONUtil.parseObj(message.getPayload());
        String id = jsonObject.getStr("id");
        String instId = jsonObject.getStr("instId");
        String state = jsonObject.getStr("state");
        Task.Result outputs = JSONUtil.toBean(jsonObject.getJSONObject("outputs"), Task.Result.class);
        Task.RetryRules retryRules = JSONUtil.toBean(jsonObject.getJSONObject("retryRules"), Task.RetryRules.class);
        String lockNode = null;
        try {
            lockNode = curatorFramework.create().withMode(CreateMode.EPHEMERAL)
                    .forPath(Inst.ZK_LOCK_PREFIX + instId, instId.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (StrUtil.isNotEmpty(lockNode)) {
            run(id, instId, state, outputs, retryRules, channel, deliveryTag, lockNode);
        } else {
            taskProductor.nAckTask(instId, deliveryTag, channel, "get inst lock fail,do nack.");
        }
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
            taskProductor.ackTask(deliveryTag, channel, "id/instId/state can't be null:id=" + id + ",instId=" + instId + ",state=" + state);
            return;
        }
        //下一个运行节点集合
        List<Task> nextTasks = new ArrayList<>();
        //找到当前任务
        Task task = taskDao.findById(id).orElse(null);
        if (task == null) {
            taskProductor.ackTask(instId, deliveryTag, channel, "do ack! task not find:" + id);
            return;
        }
        Inst inst = instDao.findById(instId).orElse(null);
        if (inst == null) {
            taskProductor.ackTask(instId, deliveryTag, channel, "do ack! inst not find:" + instId);
            return;
        } else if (Inst.STATE_SUSPEND.equals(inst.getState())) {
            inst.getSuspendNodes().add(task.getNodeName() + "|" + task.getLoopTimes());
            instDao.save(inst);
            taskProductor.ackTask(instId, deliveryTag, channel, "do ack! inst is suspend:" + instId);
            return;
        }
        //重试次数超过了5次
        if (task.getStateNackTimes() >= 5) {
            taskProductor.ackTask(instId, deliveryTag, channel, "nackTime>=5,this message may never be ack,force ack this message");
            return;
        }
        task.setEndDate(new Date());
        if (state.equals(Task.STATE_SKIP)) {
            inst.getSkipNodes().add(task.getNodeId());
        } else if (state.equals(Task.STATE_SUCCESS)) {
            inst.getSuccessNodes().add(task.getNodeId());
        } else if (state.equals(Task.STATE_FAIL)) {
            inst.getFailNodes().add(task.getNodeId());
            inst.setState(Inst.STATE_FAIL);
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
                //获取线节点，然后过滤获取强组合线节点信息
                compensateEdges = outEdges.stream().map(e -> nodeMap.get(e)).filter(c -> c.getCellType().equals(CellType.EDGE_COMPENSATE)).collect(Collectors.toList());
                outLoopEdges = outEdges.stream().map(e -> nodeMap.get(e)).filter(c -> c.getCellType().equals(CellType.EDGE_LOOP)).collect(Collectors.toList());
            }
            //任务失败，进行强组合节点回退
            if (Task.STATE_FAIL.equals(state) && compensateEdges != null && compensateEdges.size() > 0) {
                //获取最后的一个补偿节点：进行逆推
                String target = compensateEdges.get(0).getTarget().getCell();
                Cell cell = nodeMap.get(target);
                if (cell != null) {
                    TaskInterface taskInterface = SpringUtil.getBean(cell.getCellType() + TaskType.suffix);
                    //节点转换为任务
                    Task t = taskInterface.convert(cell, inst);
                    t.setStartDate(new Date());
                    taskDao.deleteByInstIdAndNodeIdAndLoopTimes(instId, t.getNodeId(), t.getLoopTimes());
                    taskDao.save(t);
                    instDao.save(inst);
                    taskProductor.sendTaskRun(t);
                    taskProductor.ackTask(instId, deliveryTag, channel, "do compensate -> node:");
                }
                return;
            }
            //任务跳过或成功，进行下一节点运行
            if (Task.STATE_SKIP.equals(state) || Task.STATE_SUCCESS.equals(state)) {
                final List<String> finalNextIds = new ArrayList<>();
                if (Task.STATE_SUCCESS.equals(state)) {
                    //循环线处理，doWhile，先执行节点再判断条件
                    if (outLoopEdges != null && outLoopEdges.size() > 0) {
                        EdgeLoopCell elc = JSONUtil.toBean(JSONUtil.parseObj(outLoopEdges.get(0)), EdgeLoopCell.class);
                        //表达式
                        String exp = elc.getData().getJsonpathElexpression();
                        String[] R = elc.getData().getR();
                        String[] L = elc.getData().getL();
                        String startId = roots.stream().findFirst().get();
                        List<FunTransform2ObjCell.Data.TransformRule> transformRules = elc.getData().getTransformRules();
                        //动态参数变化
                        EdgeLoopCell.CycleInfo cycleInfo = elc.getData().getCycleInfo();
                        if (cycleInfo != null && cycleInfo.getRule() != null) {
                            String rule = cycleInfo.getRule();
                            String stepLength = cycleInfo.getStepLength();
                            String jsonpathSelect = cycleInfo.getJsonpathSelect().split("\\$")[1];
                            List<KeyValueDto> dynamicParams = inst.getDynamicParams();
                            int idx = 0;
                            for (int i = 0; i < dynamicParams.size(); i++) {
                                if (dynamicParams.get(i).getKey().equals(jsonpathSelect)) {
                                    idx = i;
                                }
                            }
                            String dynamicValue = dynamicParams.get(idx).getKey();
                            String expressionCaculate = dynamicValue + rule + stepLength;
                            ExpressionParser parser = new SpelExpressionParser();
                            Expression expression = parser.parseExpression(expressionCaculate);
                            Object value = expression.getValue();
                            dynamicParams.get(idx).setValue(value);
                            inst.setDynamicParams(dynamicParams);
                            instDao.save(inst);
                            //决策线判断
                            Boolean flag = taskService.edgeLoopDecisionDo(startId, instId, exp, R, L, task);
                            //获取循环线之间的节点
                            Set<String> cellIdsBetweenLoopEdge = DslParser.getCellIdsBetweenLoopEdge(inst, outLoopEdges.get(0));
                            if (flag) {
                                //进入循环
                                NodeLoopInfo nodeLoopInfo = inst.getLoopRunTimesMap().get(outLoopEdges.get(0).getId());
                                nodeLoopInfo.setTimes(nodeLoopInfo.getTimes() + 1);
                                inst.getLoopRunTimesMap().put(outLoopEdges.get(0).getId(), nodeLoopInfo);
                                //将循环线之间运行过的节点重新开始
                                inst.getWaitingNodes().addAll(cellIdsBetweenLoopEdge);
                                inst.getSuccessNodes().removeAll(cellIdsBetweenLoopEdge);
                                successNodes.removeAll(cellIdsBetweenLoopEdge);
                                inst.getFailNodes().removeAll(cellIdsBetweenLoopEdge);
                                inst.getSkipNodes().removeAll(cellIdsBetweenLoopEdge);
                                instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO, "本节点的输出循环线表达式成立，将循环线中间的节点设定为未执行：" + JSONUtil.toJsonStr(cellIdsBetweenLoopEdge)));
                                //决策线下一节点：从头开始的头节点
                                String target = elc.getTarget().getCell();
                                Cell cell = inst.getNodeMap().get(target);
                                if (!StringUtils.isEmpty(cell)) {
                                    TaskInterface ti = SpringUtil.getBean(cell.getCellType() + TaskType.suffix);
                                    Task next = ti.convert(cell, inst);
                                    next.setStartDate(new Date());
                                    taskDao.deleteByInstIdAndNodeIdAndLoopTimes(instId, next.getNodeId(), next.getLoopTimes());
                                    taskDao.save(next);
                                    taskProductor.sendTaskRun(next);
                                }
                                return;
                            } else {
                                //如果不进行循环，将循环线之间节点当作成功运行了
                                inst.getWaitingNodes().removeAll(cellIdsBetweenLoopEdge);
                                waitingNodes.removeAll(cellIdsBetweenLoopEdge);
                                successNodes.addAll(cellIdsBetweenLoopEdge);
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
                    return;
                }
                //获取下一节点
                nodeChildsMap.get(task.getNodeId()).stream().forEach(i -> finalNextIds.add(i));
                List<String> whileDoNextIds = new ArrayList<>();
                //判断后面节点是否有决策线，类型是whileDo，表示先判断条件再执行节点，且决策线不需要进行循环运行，那么当前节点就需要去除掉不运行了
                //所以当前遍历需要从尾遍历，里面有remove操作
                for (int j = finalNextIds.size() - 1; j >= 0; j--) {
                    String ii = finalNextIds.get(j);
                    //入度线，先进行whileDo线判断
                    Set<String> iiInputEdges = nodeInputEdgesMap.get(ii);
                    if (null != iiInputEdges && iiInputEdges.size() > 0) {
                        //获取whileDo的线
                        List<Cell> inLoopWhileDoEdges = iiInputEdges.stream().map(e -> nodeMap.get(e)).filter(c -> {
                            boolean b1 = c.getCellType().equals(CellType.EDGE_LOOP);
                            //如果是循环线，判断线结构是不是存在whileDo逻辑
                            if (b1) {
                                EdgeLoopCell.Data data = JSONUtil.toBean(JSONUtil.toJsonStr(c.getData(), JsonSchemaUtil.jsonConfig), EdgeLoopCell.Data.class);
                                boolean b2 = LoopType.WHILE_DO.equals(data.getCycleInfo().getType());
                                return b1 && b2;
                            }
                            return b1;
                        }).collect(Collectors.toList());
                        if (null != inLoopWhileDoEdges && inLoopWhileDoEdges.size() > 0) {
                            //whileDo判断处理
                            EdgeLoopCell elc = JSONUtil.toBean(JSONUtil.parseObj(inLoopWhileDoEdges.get(0)), EdgeLoopCell.class);
                            String exp = elc.getData().getJsonpathElexpression();
                            String[] R = elc.getData().getR();
                            String[] L = elc.getData().getL();
                            String startId = roots.stream().findFirst().get();
                            List<FunTransform2ObjCell.Data.TransformRule> transformRules = elc.getData().getTransformRules();
                            //动态参数的赋值变化，如id++
                            EdgeLoopCell.CycleInfo cycleInfo = elc.getData().getCycleInfo();
                            if (null != cycleInfo.getRule()) {
                                String rule = cycleInfo.getRule();
                                String stepLength = cycleInfo.getStepLength();
                                String jsonpathSelect = cycleInfo.getJsonpathSelect().split("\\$")[1];
                                List<KeyValueDto> dynamicParams = inst.getDynamicParams();
                                int idx = 0;
                                for (int k = 0; k < dynamicParams.size(); k++) {
                                    if (dynamicParams.get(k).getKey().equals(jsonpathSelect)) {
                                        idx = k;
                                    }
                                }
                                Object dynamicValue = dynamicParams.get(idx).getValue();
                                String expressionCaculate = dynamicValue + rule + stepLength;
                                ExpressionParser parser = new SpelExpressionParser();
                                Expression expression = parser.parseExpression(expressionCaculate);
                                Object value = expression.getValue();
                                dynamicParams.get(idx).setValue(value);
                                inst.setDynamicParams(dynamicParams);
                                instDao.save(inst);
                            }
                            //判断是否需要循环
                            Boolean whileDo = taskService.edgeLoopDecisionDo(startId, instId, exp, R, L, task);
                            //不需要进行循环
                            if (!whileDo) {
                                Set<String> cellIdsBetweenLoopEdge = DslParser.getCellIdsBetweenLoopEdge(inst, inLoopWhileDoEdges.get(0));
                                inst.getWaitingNodes().removeAll(cellIdsBetweenLoopEdge);
                                inst.getSkipNodes().removeAll(cellIdsBetweenLoopEdge);
                                finalNextIds.remove(j);
                                nodeChildsMap.get(inLoopWhileDoEdges.get(0).getSource().getCell()).stream().forEach(i -> whileDoNextIds.add(i));
                            } else {
                                NodeLoopInfo nli = inst.getLoopRunTimesMap().get(outLoopEdges.get(0).getId());
                                nli.setTimes(nli.getTimes() + 1);
                                inst.getLoopRunTimesMap().put(outLoopEdges.get(0).getId(), nli);
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
                    //=========对有决策线连接的节点进行处理========
                    //获取入度线的id：线target、线id（父节点和下一节点之间的连线），其中有普通连线，循环线
                    Set<String> iiInputEdges = nodeInputEdgesMap.get(ii);
                    if (null != iiInputEdges && iiInputEdges.size() > 0) {
                        //将循环线的连接头节点去掉
                        pids = pids.stream().filter(pi -> {
                            List<String> notLoopInPids = iiInputEdges.stream().filter(ie -> !nodeMap.get(ie).getCellType().equals(CellType.EDGE_LOOP)).map(ie -> nodeMap.get(ie).getSource().getCell()).collect(Collectors.toList());
                            return notLoopInPids.contains(pi);
                        }).collect(Collectors.toSet());
                    }
                    //=========对有决策线连接的节点进行处理结束========
                    //当前节点已经成功了的父节点个数和对应的父节点个数一致，当前节点进行转换为任务运行
                    long psuccessNum = pids.stream().filter(q -> successNodes.contains(q) || skipNodes.contains(q)).count();
                    if (psuccessNum == pids.size()) {
                        Cell cell = nodeMap.get(ii);
                        if (!StringUtils.isEmpty(cell)) {
                            TaskInterface ti = SpringUtil.getBean(cell.getCellType() + TaskType.suffix);
                            Task t = ti.convert(cell, inst);
                            t.setStartDate(new Date());
                            taskDao.deleteByInstIdAndNodeIdAndLoopTimes(instId, t.getNodeId(), t.getLoopTimes());
                            taskDao.save(t);
                            //去除重复的任务
                            nextTasks.removeIf(tf -> tf.getNodeId().equals(ii));
                            nextTasks.add(t);
                        }
                    }
                }
            }
            //run task
            for (int i = 0; i < nextTasks.size(); i++) {
                Task t = nextTasks.get(i);
                Set<String> inputEdges = inst.getNodeInputEdgesMap().get(t.getNodeId());
                List<Cell> dicisionEdges = null;
                //获取决策线
                if (null != inputEdges && inputEdges.size() > 0) {
                    inputEdges.stream().map(e -> nodeMap.get(e)).filter(cell -> cell.getCellType().equals(CellType.EDGE_DECISION)).collect(Collectors.toList());
                }
                Set<String> pids = inst.getNodeParentsMap().get(t.getNodeId());
                //获取跳过节点，若父节点是跳过节点，则当前节点也是跳过
                long pSkipNum = pids.stream().filter(p -> skipNodes.contains(p)).count();
                if (pSkipNum == skipNodes.size()) {
                    instDao.save(inst);
                    t.setState(Task.STATE_SKIP);
                    taskProductor.sendTaskResult(t);
                    instLogDao.save(new InstLog(instId, t.getPlanId(), t.getNodeId(), t.getId(), InstLog.LEVEL_INFO, "节点的所有父级状态皆为SKIP，此节点也进行SKIP!"));
                } else {
                    //决策线处理
                    if (null != dicisionEdges && dicisionEdges.size() > 0) {
                        long trueCount = dicisionEdges.stream().map(e -> {
                            EdgeDecisionCell edgeDecisionCell = JSONUtil.toBean(JSONUtil.parseObj(e), EdgeDecisionCell.class);
                            String exp = edgeDecisionCell.getData().getJsonpathElexpression();
                            String[] R = edgeDecisionCell.getData().getR();
                            String[] L = edgeDecisionCell.getData().getL();
                            List<EdgeDecisionCell.ConditionData> conditionData = edgeDecisionCell.getData().getConditionData();
                            String startId = roots.stream().findFirst().get();
                            return taskService.edgeDecisionDo(startId, instId, exp, R, L, conditionData,t);
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
            taskProductor.ackTask(instId, deliveryTag, channel, "send next task success, do ack.");
        } catch (Exception e) {
            e.printStackTrace();
            inst.setState(Inst.STATE_FAIL);
            taskProductor.nAckTask(instId, deliveryTag, channel, "before send next task fail, do nack.");
        }finally {
            System.out.println("finally..........do........inst:" + inst.getId());
            if (null != task) {
                System.out.println("finally..........do........task:" + JSONUtil.toJsonPrettyStr(task));
            }
        }
    }
}
