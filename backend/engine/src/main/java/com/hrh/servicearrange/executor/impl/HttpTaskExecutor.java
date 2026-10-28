package com.hrh.servicearrange.executor.impl;

import cn.hutool.core.util.StrUtil;
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
import com.hrh.servicearrange.executor.ErrorClass;
import com.hrh.servicearrange.executor.ErrorClassifier;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.files.LocalFileStorage;
import com.hrh.servicearrange.parser.annotation.CellType;
import com.hrh.servicearrange.utils.JsonSchemaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
    /**
     * 本类方法内存在同名局部变量 {@code log}（InstLog），故日志器单独命名
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpTaskExecutor.class);

    @Value("${task.http.read-timeout:30000}")
    private int readTimeout;

    /**
     * 远程文件作为上传源时的最大字节数，防止超大文件写满磁盘（与本地存储上限同口径）
     */
    @Value("${servea.file.max-size:104857600}")
    private long maxDownloadSize;

    /**
     * 错误码分类器：把 HTTP 状态码 / 响应体业务码映射为 SUCCESS / RETRYABLE / FATAL
     */
    @Autowired
    private ErrorClassifier errorClassifier;

    /**
     * 实例文件本地存储：解析 {@code local://} 地址，远程地址下载副本不落实例目录
     */
    @Autowired
    private LocalFileStorage fileStorage;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        //需要文件上传的文件名集合
        Map<String, List<File>> fileListMap = new HashMap<>();
        //远程地址下载的临时文件，仅这些在 finally 中删除；实例目录里的正式文件必须保留
        List<File> tempFiles = new ArrayList<>();
        InstLog log = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), InstLog.LEVEL_INFO, task.getNodeName() + "节点开始执行.....");
        instLogDao.save(log);
        if (task != null) {
            try {
                String inputs = task.getInputs();
                JSONObject jsonObject = JSONUtil.parseObj(inputs);
                if (jsonObject.get("requestType") == null && jsonObject.get("requestMethod") != null) {
                    jsonObject.set("requestType", jsonObject.get("requestMethod"));
                }
                if (jsonObject.get("url") == null) {
                    if (jsonObject.get("serviceUrl") != null) {
                        jsonObject.set("url", jsonObject.get("serviceUrl"));
                    }
                }
                if (jsonObject.get("error") == null && jsonObject.get("errorno") != null) {
                    jsonObject.set("error", jsonObject.get("errorno"));
                }
                String requestType = jsonObject.get("requestType") == null ? "GET" : jsonObject.get("requestType").toString();
                String url = jsonObject.get("url") == null ? "" : jsonObject.get("url").toString();
                StringBuilder inPathParamUrl = new StringBuilder();
                inPathParamUrl.append(url);
                JSONObject input = jsonObject.getJSONObject("input");
                if (input == null) {
                    input = jsonObject.getJSONObject("inputs");
                }
                if (input == null) {
                    input = jsonObject;
                }
                Map<String, String> inBodyParam = null;
                Map<String, String> inHeaderParam = new HashMap<>();
                Inputs data = JSONUtil.toBean(input, Inputs.class);
                if (data == null) {
                    data = new Inputs();
                }
                if (input.containsKey("reqQuery")) {
                    List<KeyValueDto> reqQuery = data.getReqQuery();
                    //参数有值标识符
                    long paramCount = reqQuery == null ? 0 : reqQuery.stream().filter(bean -> bean.getValue() != null).count();
                    if (paramCount > 0) {
                        inPathParamUrl.append("?").append(reqQuery.stream()
                                .filter(bean -> bean.getValue() != null)
                                .map(bean -> encodeQuery(bean.getKey()) + "=" + encodeQuery(String.valueOf(bean.getValue())))
                                .collect(Collectors.joining("&")));
                    }
                }
                //body参数处理，处理非文件参数，文件在后面进行处理
                long bodyParamCount = 0;
                String bodyParamStr = null;
                if (input.containsKey("reqBodyForm")) {
                    List<KeyValueDto> reqBodyForm = data.getReqBodyForm();
                    bodyParamCount = reqBodyForm == null ? 0 : reqBodyForm.stream()
                            .filter(bean -> bean != null && bean.getValue() != null && !"file".equalsIgnoreCase(bean.getType()))
                            .count();
                    if (bodyParamCount > 0) {
                        inBodyParam = reqBodyForm.stream()
                                .filter(bean -> bean != null && bean.getValue() != null && !"file".equalsIgnoreCase(bean.getType()))
                                .collect(Collectors.toMap(t -> t.getKey(), t -> t.getValue().toString(), (a, b) -> b));
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
                HttpRequest httpRequest = HttpUtil.createRequest(getMethod(requestType), inPathParamUrl.toString()).addHeaders(inHeaderParam);
                //设置连接与读取超时，防止下游慢响应或无响应时无限期占用消费线程
                httpRequest = httpRequest.timeout(readTimeout).setConnectionTimeout(connectTimeout);
                if (bodyParamStr != null && !bodyParamStr.equals("{}")) {
                    httpRequest = httpRequest.body(bodyParamStr);
                }
                //收集需要上传的文件：local:// 直接使用实例目录中的文件，http(s) 下载为临时副本
                JSONObject fileUrl = jsonObject.getJSONObject("fileUrl");
                if (fileUrl != null) {
                    for (String key : fileUrl.keySet()) {
                        List<File> fileList = new ArrayList<>();
                        JSONArray array = fileUrl.getJSONArray(key);
                        if (array != null) {
                            for (String u : array.toList(String.class)) {
                                File file = toUploadFile(u, tempFiles);
                                if (file != null) {
                                    fileList.add(file);
                                } else if (StrUtil.isNotBlank(u)) {
                                    LOGGER.warn("upload file not resolved, taskId={}, url={}", task.getId(), u);
                                }
                            }
                        }
                        fileListMap.put(key, fileList);
                    }
                }
                //添加文件上传
                List<KeyValueDto> reqBodyForm = data.getReqBodyForm();
                if (reqBodyForm != null && !reqBodyForm.isEmpty()) {
                    for (KeyValueDto keyValueDto : reqBodyForm) {
                        if (keyValueDto == null || StrUtil.isBlank(keyValueDto.getKey())) {
                            continue;
                        }
                        if ("file".equalsIgnoreCase(keyValueDto.getType())) {
                            List<File> files = fileListMap.get(keyValueDto.getKey());
                            if (files == null || files.isEmpty()) {
                                //fileUrl 未声明时，允许 reqBodyForm 的 value 直接给文件地址
                                File single = toUploadFile(keyValueDto.getValue() == null
                                        ? null : String.valueOf(keyValueDto.getValue()), tempFiles);
                                if (single != null) {
                                    files = new ArrayList<>();
                                    files.add(single);
                                }
                            }
                            if (files == null || files.isEmpty()) {
                                LOGGER.warn("file param without file, skip, taskId={}, key={}", task.getId(), keyValueDto.getKey());
                                continue;
                            }
                            if (files.size() == 1) {
                                httpRequest = httpRequest.form(keyValueDto.getKey(), files.get(0));
                            } else {
                                httpRequest = httpRequest.form(keyValueDto.getKey(), files);
                            }
                        } else if (keyValueDto.getValue() != null) {
                            httpRequest = httpRequest.form(keyValueDto.getKey(), keyValueDto.getValue());
                        }
                    }
                }
                //执行http请求：try-with-resources 确保响应流与连接一定释放，避免句柄泄漏
                try (HttpResponse httpResponse = httpRequest.execute()) {
                //获取返回结果
                String responseResult = httpResponse.body();
                //获取http请求状态：200、500、404
                int status = httpResponse.getStatus();
                //获取失败的错误信息
                Object error = jsonObject.get("error");
                //按 task.error-mapping.http 映射为三态：SUCCESS / RETRYABLE / FATAL
                ErrorClassifier.Verdict verdict = errorClassifier.classify(task, "http", status, responseResult, null);
                //显式配置的 error 关键字属于业务约定的失败标记，直接判为不可重试
                if (error != null && responseResult != null && responseResult.contains(error.toString())) {
                    verdict = new ErrorClassifier.Verdict(ErrorClass.FATAL, error.toString(),
                            "命中 error 关键字：" + error + "，protocol=http");
                }
                task.setErrorClass(verdict.getErrorClass().name());
                task.setErrorCode(verdict.getCode());
                task.setErrorMessage(verdict.getMessage());
                if (verdict.isSuccess()) {
                    task.setState(Task.STATE_SUCCESS);
                    log = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), task.getNodeName() + "节点执行成功！");
                    instLogDao.save(log);
                } else {
                    task.setState(Task.STATE_FAIL);
                    log = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), task.getNodeName() + "节点执行失败！错误分类：" + verdict.getErrorClass() + "，" + verdict.getMessage());
                    instLogDao.save(log);
                    log = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(), task.getNodeName() + "节点执行失败返回信息：" + responseResult);
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
                }
            } catch (Exception e) {
                if (task != null) {
                    //异常场景无状态码，由分类器按 retryable-exceptions 前缀 + default-action 兜底
                    ErrorClassifier.Verdict verdict = errorClassifier.classify(task, "http", e);
                    task.setErrorClass(verdict.getErrorClass().name());
                    task.setErrorCode(verdict.getCode());
                    task.setErrorMessage(verdict.getMessage());
                    log = new InstLog(task.getInstId(), task.getPlanId(), task.getNodeId(), task.getId(),
                            task.getNodeName() + "节点执行异常：" + e.getMessage() + "，错误分类：" + verdict.getErrorClass());
                    instLogDao.save(log);
                    task.setState(Task.STATE_FAIL);
                }
                LOGGER.error("http task execute fail, taskId={}", task == null ? null : task.getId(), e);
            } finally {
                //只清理远程下载的临时副本，实例目录中的正式文件由实例删除流程级联清理
                tempFiles.stream().filter(f -> f != null && f.exists()).forEach(f -> {
                    if (!f.delete()) {
                        LOGGER.warn("delete temp upload file fail, path={}", f.getAbsolutePath());
                    }
                });
                fileListMap.clear();
            }
        }
        return task;
    }

    /**
     * 把文件地址解析为可上传的 {@link File}。
     * <ul>
     *     <li>{@code local://} ：实例目录中的正式文件，直接使用；</li>
     *     <li>{@code http(s)://} ：下载为临时副本，登记到 {@code tempFiles} 由调用方清理；</li>
     *     <li>其它：按本地绝对 / 相对路径处理。</li>
     * </ul>
     *
     * @return 解析不到时返回 {@code null}
     */
    private File toUploadFile(String uri, List<File> tempFiles) {
        if (StrUtil.isBlank(uri)) {
            return null;
        }
        Path local = fileStorage.resolveIfSupported(uri);
        if (local != null) {
            return Files.isRegularFile(local) ? local.toFile() : null;
        }
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            try {
                String fileName = uri.substring(uri.lastIndexOf('/') + 1);
                File temp = File.createTempFile("servea-upload-", StrUtil.isBlank(fileName) ? ".tmp" : "-" + fileName);
                HttpUtil.downloadFile(uri, temp);
                if (temp.length() > maxDownloadSize) {
                    LOGGER.warn("remote upload file exceeds limit, url={}, size={}, limit={}", uri, temp.length(), maxDownloadSize);
                    if (!temp.delete()) {
                        LOGGER.warn("delete oversized temp file fail, path={}", temp.getAbsolutePath());
                    }
                    return null;
                }
                tempFiles.add(temp);
                return temp;
            } catch (Exception e) {
                LOGGER.error("download upload file fail, url={}", uri, e);
                return null;
            }
        }
        File file = new File(uri);
        return file.isFile() ? file : null;
    }

    private String encodeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(raw, "UTF-8");
        } catch (Exception e) {
            return raw;
        }
    }

    private Method getMethod(String requestType) {
        Method method = Method.GET;
        if (requestType == null) {
            return method;
        }
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
