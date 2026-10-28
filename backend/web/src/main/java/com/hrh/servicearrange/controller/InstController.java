package com.hrh.servicearrange.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.config.OrchestrationProperties;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Plan;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.parser.DslParser;
import com.hrh.servicearrange.service.InstFileService;
import com.hrh.servicearrange.service.InstRunService;
import com.hrh.servicearrange.utils.JsonSchemaUtil;
import com.hrh.servicearrange.vo.ApiResponse;
import com.hrh.servicearrange.vo.InstRunParamsVo;
import com.hrh.servicearrange.vo.InstRunResponseVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.support.StandardMultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
@Slf4j
@RestController
@RequestMapping("/inst")
public class InstController {

    @Autowired
    private InstDao instDao;
    @Autowired
    private InstRunService instRunService;
    @Autowired
    private InstFileService instFileService;
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
        InstRunService.RunResult result = instRunService.run(planId, instRunParamsVo, paramObj,
                idempotencyKey, headerParams, standardMultipartHttpServletRequest);
        return runAck(result.getInst(), result.isIdempotentHit());
    }

    /**
     * 输出为 JSON / 文本时直接回写，为文件时交给文件服务流式下载。
     */
    private Object writeOutputs(Inst resultInst, HttpServletResponse response) throws Exception {
        InstRunResponseVo responseVo = new InstRunResponseVo();
        responseVo.setState(Inst.STATE_SUCCESS);
        Task.Result outputs = resultInst.getOutputs();
        Object respResult = resultInst.getId();
        if (outputs == null) {
            return respResult;
        }
        responseVo.getOutputs().setContentType(outputs.getContentType());
        responseVo.getOutputs().setHeaderParams(outputs.getHeaderParams());
        responseVo.getOutputs().setJsonSchema(outputs.getJsonSchema());
        String contentType = outputs.getContentType();
        if ("application/json".equals(contentType)) {
            if (JSONUtil.isJsonObj(outputs.getValue())) {
                respResult = JSONUtil.parseObj(outputs.getValue());
            } else if (JSONUtil.isJsonArray(outputs.getValue())) {
                respResult = JSONUtil.parseArray(outputs.getValue());
            } else {
                respResult = outputs.getValue();
            }
            response.setCharacterEncoding("UTF-8");
            response.setContentType(contentType);
            return respResult;
        }
        if ("text/plain".equals(contentType)) {
            response.setCharacterEncoding("UTF-8");
            response.setContentType(contentType);
            return outputs.getValue();
        }
        //非 JSON / 文本：按本地文件流式下载
        instFileService.writeDownload(outputs, response);
        return null;
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
        log.debug("request content type: {}", request.getContentType());
        //请求form-data空
        if (request.getContentType() == null || request.getParameterMap() != null) {
            if (request.getParameterMap() != null) {
                request.getParameterMap().forEach((k, v) -> formDataBody.put(k, Stream.of(v).collect(Collectors.joining(","))));
            }
        }
        return formDataBody;
    }

}
