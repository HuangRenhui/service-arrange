package com.hrh.servicearrange.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.config.OrchestrationProperties;
import com.hrh.servicearrange.convert.StartTask;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Plan;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.mq.TaskProductor;
import com.hrh.servicearrange.parser.DslParser;
import com.hrh.servicearrange.utils.JsonSchemaUtil;
import com.hrh.servicearrange.utils.TraceContext;
import com.hrh.servicearrange.vo.ApiResponse;
import com.hrh.servicearrange.vo.InstRunParamsVo;
import com.hrh.servicearrange.vo.InstRunResponseVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.StandardMultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author huangrenhui
 * @date 2022/3/31
 * @flow
 */
@RestController
@RequestMapping("/inst")
public class InstController {

    @Autowired
    private DslParser dslParser;
    @Autowired
    private InstDao instDao;
    @Autowired
    private StartTask startTask;
    @Autowired
    private TaskDao taskDao;
    @Autowired
    private TaskProductor taskProductor;
    @Autowired
    private com.hrh.servicearrange.dao.PlanDao planDao;
    @Autowired
    private OrchestrationProperties orchestration;

    /**
     * @param planId   模型id
     * @param request  参数
     * @param response
     * @return
     * @throws Exception
     */
    @PostMapping("/run/or/getResult/{planId}")
    public Object run(@PathVariable(name = "planId") String planId, HttpServletRequest request,
                      HttpServletResponse response) throws Exception {
        String contentType = null == request.getContentType() ? "null" : request.getContentType();

        StandardMultipartHttpServletRequest standardMultipartHttpServletRequest = null;
        if (contentType.contains("multipart/form-data") && request instanceof StandardMultipartHttpServletRequest) {
            standardMultipartHttpServletRequest = (StandardMultipartHttpServletRequest) request;
        }
        InstRunParamsVo instRunParamsVo = new InstRunParamsVo();
        String dslStr = loadDsl(planId);
        instRunParamsVo.setDsl(dslStr);
        //添加头信息
        Enumeration<String> headerNames = request.getHeaderNames();
        Map<String, String> headerParams = new HashMap<>(10);
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            String value = request.getHeader(name);
            if (headerParams.containsKey(name)) {
                value = headerParams.get(name) + "," + value;
            }
            headerParams.put(name, value);
        }
        //处理开始节点的jsonshema信息，转换字段类型：字段和对应类型
        JSONObject paramObj = JSONUtil.createObj(JsonSchemaUtil.jsonConfig);
        Map<String, String> dslStartParamsTypeMap = DslParser.getDslStartParamsTypeMap(dslStr);
        //处理请求的body参数，将body的值和start对应的jsonschema关系映射起来
        Map<String, String> formDataBody = convertFormDataBody(request);
        formDataBody.entrySet().stream().forEach(e -> {
            String name = e.getKey();
            String value = e.getValue();
            if (dslStartParamsTypeMap.containsKey(name)) {
                putTypedParam(paramObj, name, value, dslStartParamsTypeMap.get(name));
            } else {
                paramObj.set(name, value);
            }
        });
        instRunParamsVo.setInstName(paramObj.getStr("servea_instName"));
        instRunParamsVo.setSync(paramObj.containsKey("servea_sync") ? paramObj.getBool("servea_sync") : true);
        instRunParamsVo.setOptType(paramObj.containsKey("servea_optType") ? paramObj.getStr("servea_optType") : "run");
        String idempotencyKey = firstHeader(headerParams, "x-idempotency-key");
        if (StringUtils.isEmpty(idempotencyKey)) {
            idempotencyKey = paramObj.getStr("servea_idempotencyKey");
        }
        if (!StringUtils.isEmpty(idempotencyKey) && !"get_result".equalsIgnoreCase(instRunParamsVo.getOptType())) {
            Inst existed = instDao.findFirstByPlanIdAndIdempotencyKeyOrderByCreateDateDesc(planId, idempotencyKey);
            if (existed != null && !Inst.STATE_FAIL.equals(existed.getState())
                    && !Inst.STATE_CANCEL.equals(existed.getState())) {
                return runAck(existed, true);
            }
        }
        if ("get_result".equalsIgnoreCase(instRunParamsVo.getOptType())) {
            String queryId = paramObj.getStr("servea_instId");
            if (StringUtils.isEmpty(queryId)) {
                throw new IllegalArgumentException("get_result 需要 servea_instId");
            }
            Inst resultInst = instDao.findById(queryId).orElse(null);
            if (resultInst == null) {
                throw new IllegalArgumentException("实例不存在：" + queryId);
            }
            if (!Inst.STATE_SUCCESS.equals(resultInst.getState())) {
                throw new IllegalArgumentException("实例还未运行成功，无输出信息！");
            }
            return writeOutputs(resultInst, response);
        }
        if(null!=standardMultipartHttpServletRequest) {
            MultiValueMap<String, MultipartFile> multiFiles = standardMultipartHttpServletRequest.getMultiFileMap();
            multiFiles.keySet().stream().forEach(fileKey -> {
                MultipartFile mFile = multiFiles.getFirst(fileKey);
                if (mFile == null) {
                    return;
                }
                String originalFilename = mFile.getOriginalFilename();
                int size = Long.valueOf(mFile.getSize()).intValue();
                //进行文件保存
                System.out.println(originalFilename + ":" + size);
                paramObj.set(fileKey, "path");
            });
        }
        //dsl解析
        Inst inst = dslParser.parser(instRunParamsVo.getDsl());
        inst.setPlanId(planId);
        inst.setOptType(instRunParamsVo.getOptType());
        inst.setSync(instRunParamsVo.getSync());
        inst.setDslInputParams(JSONUtil.toJsonStr(paramObj));
        Date date = new Date();
        inst.setCreateDate(date);
        inst.setModifyDate(date);
        inst.setHeaderParams(headerParams);
        inst.setTraceId(TraceContext.getOrCreate());
        inst.setIdempotencyKey(idempotencyKey);
        inst.setCallerApp(firstHeader(headerParams, "x-caller-app"));
        Plan planMeta = planDao.findById(planId).orElse(null);
        if (planMeta != null) {
            inst.setPlanVersion(planMeta.getVersion());
            inst.setEnv(planMeta.getEnv());
        }
        instDao.save(inst);
        //返回实例运行结果
        InstRunResponseVo responseVo = new InstRunResponseVo();
        responseVo.setId(inst.getId());
        responseVo.setState(inst.getState());
        //开始运行实例
        if (instRunParamsVo.getOptType().equalsIgnoreCase("run")) {
            inst.setStarDate(new Date());
            inst.setState(Inst.STATE_RUNNING);
            instDao.save(inst);
            //从虚拟开始节点运行
            String startId = inst.getRoots() == null ? null : inst.getRoots().stream().findFirst().orElse(null);
            if (startId == null || inst.getNodeMap() == null || inst.getNodeMap().get(startId) == null) {
                throw new IllegalArgumentException("DSL 缺少可用的开始节点");
            }
            Task task = startTask.convert(inst.getNodeMap().get(startId), inst);
            taskDao.save(task);
            //mq发送开始运行
            taskProductor.sendTaskResult(task);
        }
        responseVo.setState(inst.getState());
        return runAck(inst, false);
    }

    private Object writeOutputs(Inst resultInst, HttpServletResponse response) throws Exception {
        InstRunResponseVo responseVo = new InstRunResponseVo();
        responseVo.setState(Inst.STATE_SUCCESS);
        Task.Result outputs = resultInst.getOutputs();
        Object respResult = resultInst.getId();
        if (outputs != null) {
            responseVo.getOutputs().setContentType(outputs.getContentType());
            responseVo.getOutputs().setHeaderParams(outputs.getHeaderParams());
            responseVo.getOutputs().setJsonSchema(outputs.getJsonSchema());
            response.setCharacterEncoding("UTF-8");
            if (!StringUtils.isEmpty(outputs.getContentType()) && (outputs.getContentType().equals("application/json") || outputs.getContentType().equals("text/plain"))) {
                if (outputs.getContentType().equals("application/json")) {
                    if (JSONUtil.isJsonObj(outputs.getValue())) {
                        respResult = JSONUtil.parseObj(outputs.getValue());
                    } else if (JSONUtil.isJsonArray(outputs.getValue())) {
                        respResult = JSONUtil.parseArray(outputs.getValue());
                    } else {
                        respResult = outputs.getValue();
                    }
                    response.setContentType(outputs.getContentType());
                }
            } else {
                String fileName = "download";
                String filePath = outputs.getValue();
                if (!StringUtils.isEmpty(filePath) && filePath.startsWith("filemgr://")) {
                    fileName = FileUtil.getName(filePath);
                }
                response.setHeader("content-disposition", "attachment;filename=" + URLEncoder.encode(fileName, "UTF-8"));
                if (StringUtils.isEmpty(outputs.getContentType())) {
                    response.setContentType("application/octet-stream");
                } else {
                    response.setContentType(outputs.getContentType());
                }
                response.setCharacterEncoding("UTF-8");
            }
        }
        return respResult;
    }

    private void putTypedParam(JSONObject paramObj, String name, String value, String type) {
        try {
            switch (type) {
                case "text":
                case "String":
                    paramObj.set(name, value);
                    break;
                case "jsonArrStr":
                case "array":
                    paramObj.set(name, StringUtils.isEmpty(value) ? null : JSONUtil.parseArray(value, JsonSchemaUtil.jsonConfig));
                    break;
                case "number":
                    paramObj.set(name, StringUtils.isEmpty(value) ? 0 : Long.valueOf(value));
                    break;
                case "boolean":
                    paramObj.set(name, !StringUtils.isEmpty(value) && Boolean.parseBoolean(value));
                    break;
                case "object":
                case "jsonObjStr":
                    paramObj.set(name, StringUtils.isEmpty(value) ? null : JSONUtil.parseObj(value, JsonSchemaUtil.jsonConfig));
                    break;
                default:
                    paramObj.set(name, value);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("参数 " + name + " 无法按类型 " + type + " 转换：" + e.getMessage());
        }
    }

    private String loadDsl(String planId) {
        if (StrUtil.isNotEmpty(planId) && planDao != null) {
            Plan fromDb = planDao.findById(planId).orElse(null);
            if (fromDb != null && StrUtil.isNotEmpty(fromDb.getDsl())) {
                if (Plan.STATUS_DISABLED.equals(fromDb.getStatus())) {
                    throw new IllegalArgumentException("流程已停用，无法运行");
                }
                if (orchestration != null && orchestration.isRequirePublished()
                        && !Plan.STATUS_PUBLISHED.equals(fromDb.getStatus())) {
                    throw new IllegalArgumentException("仅允许运行已发布流程，当前状态=" + fromDb.getStatus());
                }
                return fromDb.getDsl();
            }
        }
        if (orchestration != null && orchestration.isRequirePublished()) {
            throw new IllegalArgumentException("未找到已发布流程，planId=" + planId);
        }
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (StrUtil.isNotEmpty(planId)) {
            candidates.add(planId);
            if (!planId.endsWith(".json")) {
                candidates.add(planId + ".json");
                candidates.add("hrh_" + planId + ".json");
            }
        }
        candidates.add("hrh_http.json");
        for (String name : candidates) {
            try {
                org.springframework.core.io.ClassPathResource res = new org.springframework.core.io.ClassPathResource(name);
                if (res.exists()) {
                    return cn.hutool.core.io.IoUtil.readUtf8(res.getInputStream());
                }
            } catch (Exception ignored) {
            }
            if (FileUtil.exist(name)) {
                return FileUtil.readUtf8String(name);
            }
        }
        throw new IllegalArgumentException("找不到流程 DSL，planId=" + planId);
    }

    private Map<String, Object> runAck(Inst inst, boolean idempotentHit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", inst.getId());
        data.put("state", inst.getState());
        data.put("traceId", inst.getTraceId());
        data.put("planVersion", inst.getPlanVersion());
        data.put("idempotentHit", idempotentHit);
        return ApiResponse.ok(data);
    }

    private String firstHeader(Map<String, String> headers, String name) {
        if (headers == null || StringUtils.isEmpty(name)) {
            return null;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    //处理请求的body参数值
    private Map<String, String> convertFormDataBody(HttpServletRequest request) throws Exception {
        Map<String, String> formDataBody = new HashMap<>();
        System.out.println(request.getContentType());
        //请求form-data空
        if (request.getContentType() == null || request.getParameterMap() != null) {
            if (request.getParameterMap() != null) {
                request.getParameterMap().forEach((k, v) -> formDataBody.put(k, Stream.of(v).collect(Collectors.joining(","))));
            }
        }
        return formDataBody;
    }

    private static byte[] readInputStream(InputStream inputStream) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            outputStream.close();
            inputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return outputStream.toByteArray();
    }
}
