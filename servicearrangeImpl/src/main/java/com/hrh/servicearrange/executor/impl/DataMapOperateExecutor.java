package com.hrh.servicearrange.executor.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.script.JavaScriptEngine;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.dsl.FunDatamapCell;
import com.hrh.servicearrange.dsl.KeyValueDto;
import com.hrh.servicearrange.dsl.KeyValueWithJsonPathDto;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.NodeLoopInfo;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author huangrenhui
 * @date 2022/4/29
 * @flow 数据映射，将表达式映射为具体的值
 */
@Service(value = CellType.FUN_DATAMAP)
public class DataMapOperateExecutor implements Execute {
    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        String inputs = task.getInputs();
        JSONObject jsonObject = JSONUtil.parseObj(inputs);
        JSONObject pouts = JSONUtil.createObj();
        pouts.set("staticParams", inst.getStaticParams());
        pouts.set("dynamicParams", inst.getDynamicParams());
        Task startTask = taskDao.findStartTask(task.getInstId(), CellType.START);
        pouts.set(startTask.getId(), startTask.getOutputs());
        String[] id1s = StrUtil.subBetweenAll(inputs, "#pno_", "$");
        String[] id2s = StrUtil.subBetweenAll(inputs, "#header_", "$");
        Set<String> nodeIds = Stream.of(id1s).collect(Collectors.toSet());
        nodeIds.addAll(Stream.of(id2s).filter(s -> !StringUtils.isEmpty(s)).collect(Collectors.toSet()));
        //获取所有节点任务
        List<Task> taskList = taskDao.findAllByInstIdAndNodeIdIn(task.getInstId(), nodeIds);
        if (taskList != null && taskList.size() > 0) {
            taskList.stream().forEach(t -> {
                String loopTime = "0";
                List<NodeLoopInfo> loopInfos = inst.getLoopRunTimesMap().entrySet().stream().filter(e -> e.getValue().getIdsBetweenLoopEdge().contains(t.getNodeId())).map(e -> e.getValue()).collect(Collectors.toList());
                if (loopInfos != null && loopInfos.size() > 0) {
                    NodeLoopInfo near = loopInfos.stream().sorted((n1, n2) -> n1.getIdsBetweenLoopEdge().size() - n2.getIdsBetweenLoopEdge().size()).findFirst().get();
                    loopTime = near.getLoopEdgeId() + "|" + near.getTimes();
                }
                if (loopTime.equals(t.getLoopTimes())) {
                    pouts.set(t.getNodeId(), t.getOutputs());
                }
            });
        }

        FunDatamapCell.Data.ChildInput cin = JSONUtil.toBean(jsonObject.getJSONObject("childInputs"), FunDatamapCell.Data.ChildInput.class);
        if (cin != null) {
            if (null != cin.getReqPath() && cin.getReqPath().size() > 0) {
                valueReplace(cin.getReqPath(), startTask.getNodeId(), pouts, task, instLogDao);
            }
            if (null != cin.getReqQuery() && cin.getReqQuery().size() > 0) {
                valueReplace(cin.getReqQuery(), startTask.getNodeId(), pouts, task, instLogDao);
            }
            if (null != cin.getReqHeaders() && cin.getReqHeaders().size() > 0) {
                valueReplace(cin.getReqHeaders(), startTask.getNodeId(), pouts, task, instLogDao);
            }
            if (null != cin.getReqBodyForm() && cin.getReqBodyForm().size() > 0) {
                valueReplace(cin.getReqBodyForm(), startTask.getNodeId(), pouts, task, instLogDao);
            }
            //处理jsonschema结构:{"type":"object","properties":{"projectCode":{"type":"string"},"name":{"type":"string"},"wftype":{"type":"number"},"gpid":{"type":"string"},"comment":{"type":"string"}}}
            //{
            //	"type": "object",
            //	"properties": {
            //		"projectCode": {
            //			"type": "string"
            //		},
            //		"name": {
            //			"type": "string"
            //		},
            //		"wftype": {
            //			"type": "number"
            //		},
            //		"gpid": {
            //			"type": "string"
            //		},
            //		"comment": {
            //			"type": "string"
            //		}
            //	}
            //}
            if (!StringUtils.isEmpty(cin.getReqBodyOther())) {
                JSONObject jsonSchemaObj = JSONUtil.parseObj(cin.getReqBodyOther());
                JSONObject properties = jsonObject.getJSONObject("properties");
                properties.keySet().stream().forEach(key -> {
                    JSONObject obj = properties.getJSONObject(key);
                    Object type = obj.get("type");
                    Object value = obj.get("defaultValue");
                    String jsonpathMapping = obj.containsKey("jsonpathMapping") && !StringUtils.isEmpty(obj.getStr("jsonpathMapping")) ? obj.getStr("jsonpathMapping") : null;
                    if (!StringUtils.isEmpty(jsonpathMapping)) {
                        value = valueCompute(startTask.getNodeId(), jsonpathMapping, pouts, task, instLogDao);
                    } else if ("object".equals(type) && obj.containsKey("properties")) {
                        //多重对象嵌套
                        doNextProperties(obj, startTask.getNodeId(), pouts, task, instLogDao);
                    }
                    obj.set("value", value);
                    properties.set(key, obj);
                });
                jsonSchemaObj.set("properties", properties);
                cin.setReqBodyOther(JSONUtil.toJsonStr(jsonSchemaObj));
            }
            task.getOutputs().setValue(JSONUtil.toJsonStr(cin));
        }
        task.setState(Task.STATE_SUCCESS);
        return task;
    }

    //处理多重对象嵌套
    private void doNextProperties(JSONObject jsonSchemaObj, String startId, JSONObject pouts, Task task, InstLogDao instLogDao) {
        JSONObject properties = jsonSchemaObj.getJSONObject("properties");
        properties.keySet().stream().forEach(key -> {
            JSONObject obj = properties.getJSONObject(key);
            Object type = obj.get("type");
            Object value = obj.get("defaultValue");
            String jsonpathMapping = obj.containsKey("jsonpathMapping") && !StringUtils.isEmpty(obj.getStr("jsonpathMapping")) ? obj.getStr("jsonpathMapping") : null;
            if (!StringUtils.isEmpty(jsonpathMapping)) {
                value = valueCompute(startId, jsonpathMapping, pouts, task, instLogDao);
            } else if ("object".equals(type) && obj.containsKey("properties")) {
                //多重对象嵌套
                doNextProperties(obj, startId, pouts, task, instLogDao);
            }
            obj.set("value", value);
            properties.set(key, obj);
        });
        jsonSchemaObj.set("properties", properties);
    }

    //获取值
    private void valueReplace(List<KeyValueWithJsonPathDto> dtos, String startNodeId, JSONObject pouts, Task task, InstLogDao instLogDao) {
        dtos.stream().forEach(dto -> {
            if (StringUtils.isEmpty(dto.getJsonpathMapping())) {
                dto.setValue(dto.getDefaultValue());
            } else {
                String jsonpathMapping = dto.getJsonpathMapping();
                Object valueCompute = valueCompute(startNodeId, jsonpathMapping, pouts, task, instLogDao);
                dto.setValue(valueCompute);
            }
        });
    }

    //值计算
    public Object valueCompute(String startId, String jsonpathMapping, JSONObject pouts, Task task, InstLogDao instLogDao) {
        Object value = null;
        if (StringUtils.isEmpty(jsonpathMapping)) {
            return value;
        }
        //对中文括号进行替换
        jsonpathMapping = jsonpathMapping.replace("（", "(").replace("）", ")");
        String finalJsonpathMapping = StrUtil.trim(jsonpathMapping, 0);
        //||
        boolean hasStringSplicer = hasStringSplicer(finalJsonpathMapping);
        // + - * / ( )
        boolean hasCalculationSymbol = hasCalculationSymbol(finalJsonpathMapping);
        //没有|| + - * / ( )
        if (!hasStringSplicer && !hasCalculationSymbol) {
            value = valueConvert(startId, finalJsonpathMapping, pouts, task, instLogDao);
        } else {
            //有+ - * / ( )
            if (hasCalculationSymbol) {
                //没有||
                if (!hasStringSplicer) {
                    value = getCalculationSymbolValue(startId, finalJsonpathMapping, pouts, task, instLogDao);
                }
            } else {
                //有||，进行切割分别计算
                value = Stream.of(finalJsonpathMapping.split("\\|\\|")).map(s -> {
                    String v = s;
                    if (s.contains("#")) {
                        Object r = getCalculationSymbolValue(startId, s, pouts, task, instLogDao);
                        v = r.toString();
                    }
                    return v;
                }).collect(Collectors.joining());
            }
        }
        return value;
    }

    //表达式存在+ - * / ( )进行js引擎计算：(#pno_1$age+#pno_2$age) * #pno_3$number
    //后续采用Spel进行替换
    private Object getCalculationSymbolValue(String startId, String finalJsonpathMapping, JSONObject pouts, Task task, InstLogDao instLogDao) {
        Object value = null;
        //获取所有的key和包含表达式：(pno_1$age+、pno_2$age)*、pno_3$number
        String[] arr = finalJsonpathMapping.split("#");
        //将key和符号切割出来到集合中：pno_1$age、+、pno_2$age
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < arr.length; i++) {
            //（pno_1$age+、pno_2$age)*、pno_3$number
            String str = arr[i];
            //如果是符号 + - * / ()，直接加入集合
            if (allCalculationSymbol(str)) {
                keys.add(str);
            } else {
                //将key和符号切割出来
                //转换为数组
                char[] chars = str.toCharArray();
                int num = 0;
                //符号基本在最后一位，所以从后面开始遍历，获取到最后一位符号的索引
                for (int j = chars.length - 1; j > 0; j--) {
                    String s = String.valueOf(chars[j]);
                    if ("".equals(s.trim()) || allCalculationSymbol(s)) {
                        num++;
                        continue;
                    } else {
                        break;
                    }
                }
                //将key和符号分别切割出来
                String key = str.substring(0, str.length() - num);
                String symbol = str.substring(str.length() - num);
                keys.add("#" + key);
                keys.add(symbol);
            }
        }
        String valueExp = keys.stream().map(s -> {
            Object v = s;
            if (s.startsWith("#")) {
                v = valueConvert(startId, finalJsonpathMapping, pouts, task, instLogDao);
            }
            return v.toString();
        }).collect(Collectors.joining());
        JavaScriptEngine jse = JavaScriptEngine.instance();
        try {
            jse.eval("var obj=" + valueExp);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
        value = jse.get("obj");
        return value;
    }

    //判断是否存在 + - * / ()
    private boolean allCalculationSymbol(String str) {
        String regex = "\\+|\\-|\\*|\\/|\\(|\\)";
        Pattern pt = Pattern.compile(regex);
        String[] splits = pt.split(str);
        long count = Stream.of(splits).filter(s -> !StringUtils.isEmpty(s)).count();
        return count <= 0;
    }

    //值转换
    private Object valueConvert(String startId, String jsonpathMapping, JSONObject pouts, Task task, InstLogDao instLogDao) {
        Object value = null;
        if (org.springframework.util.StringUtils.isEmpty(jsonpathMapping)) {
            return value;
        }
        Boolean hasStringSplicer = hasStringSplicer(jsonpathMapping);
        Boolean hasCalculationSymbol = hasCalculationSymbol(jsonpathMapping);
        if (hasStringSplicer || hasCalculationSymbol) {
            throw new RuntimeException("对于jsonpath的转换不能包含字符串拼接符||和计算符+-*/");
        }
        //对各个节点进行计算
        //开始节点
        if (jsonpathMapping.startsWith("#start")) {
            Task.Result r = JSONUtil.toBean(pouts.getJSONObject(startId), Task.Result.class);
            //获取映射节点
            String expression = jsonpathMapping.startsWith("#start_") ? jsonpathMapping.replace("#start_$", "") : jsonpathMapping.replace("#start$", "");
            //出参值是一个对象
            JSON parse = JSONUtil.parse(r.getValue());
            JSONObject jsonObject = JSONUtil.parseObj(r.getValue());
            //获取值
            if (jsonObject.containsKey(expression)) {
                value = jsonObject.getObj(expression);
            } else {
                //获取嵌套对象，比如数组等，person.name、person.friends[5].name
                value = JSONUtil.getByPath(parse, expression);
            }
            //普通节点
        } else if (jsonpathMapping.startsWith("#pno_")) {
            //节点id
            String id = StrUtil.subBetween(jsonpathMapping, "#pno_", "$");
            Task.Result r = JSONUtil.toBean(pouts.getJSONObject(id), Task.Result.class);
            //获取表达式
            String expression = jsonpathMapping.replace("#pno_" + id + "$", "");
            if (r != null) {
                //对象
                JSON json = JSONUtil.parse(r.getValue());
                JSONObject jsonObject = JSONUtil.parseObj(r.getValue());
                if (jsonObject.equals(expression)) {
                    value = jsonObject.get(expression);
                } else {
                    //多重数组计算：#pno_id$features[i].obj[j].name
                    //先判断是否存在索引越界问题
                    if (expression.contains("[") && expression.contains("]")) {
                        int fromIndex = 0;
                        //最后一个“[”索引
                        int lastIndex = expression.lastIndexOf("[");
                        //第一个“[”索引
                        int indexOfL = expression.indexOf("[", fromIndex);
                        //开始doWhile，处理第一个数组，后面有多个数组再进行处理
                        if (indexOfL > 0) {
                            do {
                                //对所有“[”进行索引定位
                                indexOfL = expression.indexOf("[", fromIndex);
                                int indexOfR = expression.indexOf("]", fromIndex);
                                //获取数组的索引
                                String idxStr = expression.substring(indexOfL + 1, indexOfR);
                                //获取数组名称
                                String expressionP = expression.substring(0, indexOfL);
                                //获取数组数据
                                Object expressionArrayObj = JSONUtil.getByPath(json, expressionP);
                                //转换为数组对象
                                JSONArray expressionArr = JSONUtil.parseArray(expressionArrayObj);
                                int idxInt = Integer.valueOf(idxStr);
                                //判断索引是否越界
                                if (idxInt > expressionArr.size()) {
                                    String erroMsg = "下标越界！请检查获取条件：" + jsonpathMapping + "; " + expression;
                                    instLogDao.save(new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_ERRO, erroMsg));
                                    task.setState(Task.STATE_FAIL);
                                    return null;
                                }
                                fromIndex = indexOfL;
                            } while (indexOfL != lastIndex);
                        }
                    }
                    //获取值
                    value = JSONUtil.getByPath(json, expression);
                }
            }
        } else if (jsonpathMapping != null && jsonpathMapping.startsWith("#header_")) {
            //请求header参数映射
            String id = StrUtil.subBetween(jsonpathMapping, "#header_", "$");
            JSONObject rObj = org.springframework.util.StringUtils.isEmpty(id) ? pouts.getJSONObject(startId) : pouts.getJSONObject(id);
            Task.Result r = JSONUtil.toBean(rObj, Task.Result.class);
            String expression = jsonpathMapping.replace("#header_" + id + "$", "");
            value = r.getHeaderParams().get(expression);
        } else if (jsonpathMapping != null && jsonpathMapping.startsWith("#staticParams")) {
            //全局静态参数
            List<KeyValueDto> staticParams = JSONUtil.toList(pouts.getJSONArray("staticParams"), KeyValueDto.class);
            if (staticParams != null) {
                String expression = jsonpathMapping.startsWith("#staticParams_") ? jsonpathMapping.replace("#staticParams_$", "") : jsonpathMapping.replace("#staticParams$", "");
                KeyValueDto keyValueDto = staticParams.stream().filter(dto -> dto.getKey().equals(expression)).findFirst().get();
                value = keyValueDto.getValue();
            }
        } else if (jsonpathMapping.startsWith("#dynamicParams")) {
            //处理全局动态参数
            List<KeyValueDto> dynamicParams = JSONUtil.toList(pouts.getJSONArray("dynamicParams"), KeyValueDto.class);
            if (dynamicParams != null) {
                String expression = jsonpathMapping.startsWith("#dynamicParams_") ? jsonpathMapping.replace("#dynamicParams_$", "") : jsonpathMapping.replace("#dynamicParams$", "");
                KeyValueDto keyValueDto = dynamicParams.stream().filter(dto -> dto.getKey().equals(expression)).findFirst().get();
                value = keyValueDto.getValue();
            }
        }
        return value;
    }

    private boolean hasCalculationSymbol(String str) {
        return str.contains("||");
    }

    //String a = "#pno$A+#pno$B";
    public static boolean hasStringSplicer(String str) {
        boolean b1 = str.contains("+") || str.contains("-") || str.contains("*") || str.contains("/");
        String regex = "#|\\+#|\\-#|\\*#|\\/#|\\(#|\\)";
        Pattern pt = Pattern.compile(regex);
        String[] split = pt.split(str);
        long count = Stream.of(split).filter(s -> !StringUtils.isEmpty(s)).filter(s -> s.startsWith("#")).count();
        boolean b2 = count <= 0;
        return b1 && b2;
    }

    public static void main(String[] args) {
        String a = "(#pno_1$age+#pno_2$age) * #pno_3$number";
    }

}
