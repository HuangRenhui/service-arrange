package com.hrh.servicearrange.executor.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.dsl.KeyValueDto;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.InstLog;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import com.hrh.servicearrange.utils.JsonSchemaUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author huangrenhui
 * @date 2022/5/11
 * @flow 案例：
 * {
 * "requestType": "POST",
 * "url": "http://192.168.50.12:8778/jsyd-openapi/query/pgquery",
 * "input": "{\"reqQuery\":[],\"reqHeaders\":[],\"reqBodyOther\":\"{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{\\\"layer\\\":{\\\"jsonpathMapping\\\":\\\"#staticParams_$layer\\\",\\\"type\\\":\\\"string\\\",\\\"value\\\":\\\"jsyd_slk\\\"},\\\"where\\\":{\\\"jsonpathMapping\\\":\\\"#start_$where\\\",\\\"type\\\":\\\"string\\\",\\\"value\\\":\\\"xmmc='城镇分批次测试'\\\"},\\\"dbUrl\\\":{\\\"jsonpathMapping\\\":\\\"#staticParams_$dbUrl\\\",\\\"type\\\":\\\"string\\\",\\\"value\\\":\\\"pgdb://192.168.20.237:5432/imap_dms_new/imap_gzt?username=sde&password=Southgis@5432\\\"}}}\",\"reqBodyForm\":[]}",
 * "fileUrl": {}
 * }
 */
@Service(CellType.OPERATOR_HTTP)
public class HttpTaskExecutor implements Execute {

    /**
     * HTTP 连接超时：单位毫秒。避免下游不可达时长时间占用消费线程
     */
    @Value("${task.http.connect-timeout:5000}")
    private int connectTimeout;

    /**
     * HTTP 读取超时：单位毫秒。避免下游挂起导致消费线程被无限期占用（消费并发默认为 1，会阻塞整条链路）
     */
    @Value("${task.http.read-timeout:30000}")
    private int readTimeout;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        //需要文件上传的文件名集合
        Map<String, List<File>> fileListMap = new HashMap<>();
        InstLog log = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO, task.getNodeName() + "节点开始执行.....");
        instLogDao.save(log);
        if (task != null) {
            try {
                String inputs = task.getInputs();
                JSONObject jsonObject = JSONUtil.parseObj(inputs);
                //请求类型
                String requestType = jsonObject.get("requestType").toString();
                String url = jsonObject.get("url").toString();
                //url后面拼接参数
                StringBuilder inPathParamUrl = new StringBuilder();
                inPathParamUrl.append(url);
                //入参数据：body、header
                JSONObject input = jsonObject.getJSONObject("input");
                //body
                Map<String, String> inBodyParam = null;
                //header
                Map<String, String> inHeaderParam = new HashMap<>();
                //入参数据转换为具体对象，容易解析获取
                Inputs data = JSONUtil.toBean(input, Inputs.class);
                //url path参数
                if (input.containsKey("reqQuery")) {
                    List<KeyValueDto> reqQuery = data.getReqQuery();
                    //参数有值标识符
                    long paramCount = reqQuery == null ? 0 : reqQuery.stream().filter(bean -> bean.getValue() != null).count();
                    if (paramCount > 0) {
                        inPathParamUrl.append("?").append(reqQuery.stream().map(bean -> bean.getKey() + "=" + bean.getValue()).collect(Collectors.toList()).stream().collect(Collectors.joining("&")));
                    }
                }
                //body参数处理，处理非文件参数，文件在后面进行处理
                long bodyParamCount = 0;
                String bodyParamStr = null;
                if (input.containsKey("reqBodyForm")) {
                    List<KeyValueDto> reqBodyForm = data.getReqBodyForm();
                    bodyParamCount = reqBodyForm == null ? 0 : reqBodyForm.stream().filter(bean -> bean.getValue() != null && !bean.getType().equals("file")).count();
                    if (bodyParamCount > 0) {
                        inBodyParam = reqBodyForm.stream().filter(bean -> bean.getValue() != null && !bean.getType().equals("file")).collect(Collectors.toMap(t -> t.getKey(), t -> t.getValue().toString()));
                        bodyParamStr = JSONUtil.toJsonStr(inBodyParam);
                    }
                }
                /**
                 * {
                 * 	"type": "object",
                 * 	"properties": {
                 * 		"layer": {
                 * 			"jsonpathMapping": "#staticParams_$layer",
                 * 			"type": "string",
                 * 			"value": "jsyd_slk"
                 *                },
                 * 		"where": {
                 * 			"jsonpathMapping": "#start_$where",
                 * 			"type": "string",
                 * 			"value": "xmmc='城镇分批次测试'"
                 *        },
                 * 		"dbUrl": {
                 * 			"jsonpathMapping": "#staticParams_$dbUrl",
                 * 			"type": "string",
                 * 			"value": "pgdb://192.168.20.237:5432/imap_dms_new/imap_gzt?username=sde&password=Southgis@5432"
                 *        }* 	}
                 * }
                 */
                if (input.containsKey("reqBodyOther") && bodyParamCount == 0) {
                    //jsonshema格式
                    JSONObject reqBodyJson = JSONUtil.parseObj(data.getReqBodyOther());
                    //类型：对象或者数组
                    String type = reqBodyJson.getStr("type");
                    if ("object".equals(type)) {
                        JSONObject bodyJson = JsonSchemaUtil.jsonSchema2JsonObject(data.getReqBodyOther());
                        bodyParamStr = JSONUtil.toJsonStr(bodyJson, JsonSchemaUtil.jsonConfig);
                    } else if ("array".equals(type)) {
                        JSONArray bodyJson = JsonSchemaUtil.jsonSchema2JsonArray(data.getReqBodyOther());
                        bodyParamStr = JSONUtil.toJsonStr(bodyJson, JsonSchemaUtil.jsonConfig);
                    }
                }
                //处理header
                if (input.containsKey("reqHeaders")) {
                    List<KeyValueDto> reqHeaders = data.getReqHeaders();
                    long headerParamCount = reqHeaders == null ? 0 : reqHeaders.stream().filter(bean -> bean.getValue() != null).count();
                    if (headerParamCount > 0) {
                        inHeaderParam = reqHeaders.stream().filter(bean -> bean.getValue() != null).collect(Collectors.toMap(t -> t.getKey(), t -> t.getValue().toString()));
                    }
                }
                HttpResponse httpResponse = null;
                HttpRequest httpRequest = HttpUtil.createRequest(getMethod(requestType), inPathParamUrl.toString()).addHeaders(inHeaderParam);
                //设置连接与读取超时，防止下游慢响应或无响应时无限期占用消费线程
                httpRequest = httpRequest.timeout(readTimeout).setConnectionTimeout(connectTimeout);
                if (bodyParamStr != null && !bodyParamStr.equals("{}")) {
                    httpRequest = httpRequest.body(bodyParamStr);
                }
                //获取文件：根据文件地址获取到文件流，封装到File中，缓存到本地中
                JSONObject fileUrl = jsonObject.getJSONObject("fileUrl");
                if (fileUrl != null) {
                    fileUrl.keySet().stream().forEach(key -> {
                        List<File> fileList = new ArrayList<>();
                        //多个文件
                        JSONArray array = fileUrl.getJSONArray(key);
                        List<String> urlList = array.toList(String.class);
                        urlList.forEach(u -> {
                            int index = u.lastIndexOf("/");
                            int length = u.length();
                            String fileName = u.substring(index + 1, length);
                            //获取文件添加到fileList中
                            // FileUtil.writeFromStream(inputStream, file);
                            // fileList.add(file);
                        });
                        fileListMap.put(key, fileList);
                    });
                }
                //添加文件上传
                List<KeyValueDto> reqBodyForm = data.getReqBodyForm();
                if (reqBodyForm != null && reqBodyForm.size() > 0) {
                    for (int i = 0; i < reqBodyForm.size(); i++) {
                        KeyValueDto keyValueDto = reqBodyForm.get(i);
                        if (keyValueDto.getType().equals("file") && fileListMap != null && fileListMap.size() > 0) {
                            if (fileListMap.get(keyValueDto.getKey()).size() == 1) {
                                File f = fileListMap.get(keyValueDto.getKey()).get(0);
                                httpRequest = httpRequest.form(keyValueDto.getKey(), f);
                            } else {
                                httpRequest = httpRequest.form(keyValueDto.getKey(), fileListMap.get(keyValueDto.getKey()));
                            }
                        } else {
                            httpRequest = httpRequest.form(keyValueDto.getKey(), keyValueDto.getValue());
                        }
                    }
                }
                //执行http请求
                httpResponse = httpRequest.execute();
                //获取返回结果
                String responseResult = httpResponse.body();
                //获取http请求状态：200、500、404
                int status = httpResponse.getStatus();
                //获取失败的错误信息
                Object error = jsonObject.get("error");
                if (status != 200 || (error != null && responseResult.contains(error.toString()))) {
                    task.setState(Task.STATE_FAIL);
                    log = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), task.getNodeName() + "节点执行失败！");
                    instLogDao.save(log);
                    log = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), task.getNodeName() + "节点执行失败返回信息：" + responseResult);
                    instLogDao.save(log);
                } else {
                    task.setState(Task.STATE_SUCCESS);
                    log = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), task.getNodeName() + "节点执行成功！");
                    instLogDao.save(log);
                }
                //记录请求的返回信息、content-type、返回的定义信息schema、下一节点的头信息
                Task.Result result = new Task.Result(responseResult);
                result.setContentType(task.getOutputs().getContentType());
                result.setJsonSchema(task.getOutputs().getJsonSchema());
                Map<String, String> headerParams = task.getOutputs().getHeaderParams();
                if (headerParams != null && headerParams.size() > 0) {
                    inHeaderParam.entrySet().stream().forEach(e -> headerParams.put(e.getKey(), e.getValue()));
                }
                result.setHeaderParams(headerParams);
                task.setOutputs(result);
            } catch (Exception e) {
                if (task != null) {
                    log = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), task.getNodeName() + "节点执行异常：" + e.getMessage());
                    instLogDao.save(log);
                    task.setState(Task.STATE_FAIL);
                }
                e.printStackTrace();
            } finally {
                //将缓存的本地文件进行删除
                if(fileListMap!=null&&fileListMap.size()>0){
                    fileListMap.entrySet().stream().forEach(k->fileListMap.get(k).stream().forEach(f->f.delete()));
                }
            }
        }
        return task;
    }

    private Method getMethod(String requestType) {
        Method method = Method.GET;
        switch (requestType) {
            case "GET":
                method = Method.GET;
                break;
            case "POST":
                method = Method.POST;
                break;
            case "PUT":
                method = Method.PUT;
                break;
        }
        return method;
    }

    private Inputs inputs;

    public class Inputs {
        private List<KeyValueDto> reqQuery;
        private List<KeyValueDto> reqHeaders;
        private List<KeyValueDto> reqBodyForm;
        private String reqBodyOther;

        public List<KeyValueDto> getReqQuery() {
            return reqQuery;
        }

        public void setReqQuery(List<KeyValueDto> reqQuery) {
            this.reqQuery = reqQuery;
        }

        public List<KeyValueDto> getReqHeaders() {
            return reqHeaders;
        }

        public void setReqHeaders(List<KeyValueDto> reqHeaders) {
            this.reqHeaders = reqHeaders;
        }

        public List<KeyValueDto> getReqBodyForm() {
            return reqBodyForm;
        }

        public void setReqBodyForm(List<KeyValueDto> reqBodyForm) {
            this.reqBodyForm = reqBodyForm;
        }

        public String getReqBodyOther() {
            return reqBodyOther;
        }

        public void setReqBodyOther(String reqBodyOther) {
            this.reqBodyOther = reqBodyOther;
        }
    }

    public Inputs getInputs() {
        return inputs;
    }

    public void setInputs(Inputs inputs) {
        this.inputs = inputs;
    }
}
