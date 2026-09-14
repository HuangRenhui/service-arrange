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
import com.hrh.servicearrange.dsl.Cell;
import com.hrh.servicearrange.dsl.EdgeLoopCell;
import com.hrh.servicearrange.dsl.FunTransform2ObjCell;
import com.hrh.servicearrange.dsl.KeyValueDto;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.NodeLoopInfo;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.parser.annotation.CellType;
import com.hrh.servicearrange.parser.annotation.TaskType;
import com.hrh.servicearrange.service.ITaskService;
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
                    //循环线处理
                    if (outLoopEdges != null && outLoopEdges.size() > 0) {
                        EdgeLoopCell elc = JSONUtil.toBean(JSONUtil.parseObj(outLoopEdges.get(0)), EdgeLoopCell.class);
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
                            Boolean flag = taskService.edgeLoopDecisionDo(startId, instId, exp, R, L, transformRules, task);
                            if(flag){
                                //进入循环
                                NodeLoopInfo nodeLoopInfo = inst.getLoopRunTimesMap().get(outLoopEdges.get(0).getId());
                                nodeLoopInfo.setTimes(nodeLoopInfo.getTimes()+1);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
