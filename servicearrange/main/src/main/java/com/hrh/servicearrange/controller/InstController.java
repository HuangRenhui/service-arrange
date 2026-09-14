package com.hrh.servicearrange.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.convert.StartTask;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.mq.TaskProductor;
import com.hrh.servicearrange.parser.DslParser;
import com.hrh.servicearrange.utils.JsonSchemaUtil;
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
        if (contentType.contains("multipart/form-data")) {
            standardMultipartHttpServletRequest = (StandardMultipartHttpServletRequest) request;
        } else {
//            throw new RuntimeException("请以ContentType=multipart/form-data进行POST提交！当前ContentType=" + contentType);
        }
        InstRunParamsVo instRunParamsVo = new InstRunParamsVo();
        String dslStr = FileUtil.readUtf8String("hrh_http.json");
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
                switch (dslStartParamsTypeMap.get(name)) {
                    case "text":
                    case "String":
                        paramObj.set(name, value);
                        break;
                    case "jsonArrStr":
                    case "array":
                        JSONArray array = StringUtils.isEmpty(value) ? null : JSONUtil.parseArray(value, JsonSchemaUtil.jsonConfig);
                        paramObj.set(name, array);
                        break;
                    case "number":
                        Long number = StringUtils.isEmpty(value) ? 0 : Long.valueOf(value);
                        paramObj.set(name, number);
                        break;
                    case "boolean":
                        Boolean booleant = StringUtils.isEmpty(value) ? false : Boolean.valueOf(value);
                        paramObj.set(name, booleant);
                        break;
                    case "object":
                    case "jsonObjStr":
                        JSONObject jsonObject = StringUtils.isEmpty(value) ? null : JSONUtil.parseObj(value, JsonSchemaUtil.jsonConfig);
                        paramObj.set(name, jsonObject);
                        break;
                    default:
                        break;

                }
            } else {
                paramObj.set(name, value);
            }
        });
        instRunParamsVo.setInstName(paramObj.getStr("servea_instName"));
        instRunParamsVo.setSync(paramObj.containsKey("servea_sync") ? paramObj.getBool("servea_sync") : true);
        instRunParamsVo.setOptType(paramObj.containsKey("servea_optType") ? paramObj.getStr("servea_optType") : "run");
        //处理请求的文件
        if(null!=standardMultipartHttpServletRequest) {
            MultiValueMap<String, MultipartFile> multiFiles = standardMultipartHttpServletRequest.getMultiFileMap();
            multiFiles.keySet().stream().forEach(fileKey -> {
                MultipartFile mFile = multiFiles.getFirst(fileKey);
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
            String startId = inst.getRoots().stream().findFirst().get();
            Task task = startTask.convert(inst.getNodeMap().get(startId), inst);
            taskDao.save(task);
            //mq发送开始运行
            taskProductor.sendTaskResult(task);
        }
        boolean returnOutPuts = false;
        //设置实例运行返回结果的状态
        responseVo.setState(inst.getState());
        if (instRunParamsVo.getOptType().equalsIgnoreCase("get_result")) {
            Inst stateInst = instDao.findStateById(inst.getId());
            if (!Inst.STATE_SUCCESS.equals(stateInst.getState())) {
                throw new RuntimeException("实例还未运行成功，无输出信息！");
            } else {
                returnOutPuts = true;
            }
        }
        Object respResult = inst.getId();
        if (returnOutPuts) {
            responseVo.setState(Inst.STATE_SUCCESS);
            Inst resultInst = instDao.findOutputsById(inst.getId());
            Task.Result outputs = resultInst.getOutputs();
            //设置请求返回结果
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
                    //文件
                    InputStream inputStream = null;
                    OutputStream outputStream = null;
                    String fileName = null;
                    String filePath = outputs.getValue();
                    if (filePath.startsWith("filemgr://")) {
                        fileName = FileUtil.getName(filePath);
                    }
                    response.setHeader("content-disposition", "attachment;filename=" + URLEncoder.encode(fileName, "UTF-8"));
                    if (StringUtils.isEmpty(outputs.getContentType())) {
                        response.setContentType("application/octet-stream");
                    } else {
                        response.setContentType(outputs.getContentType());
                    }
                    response.setCharacterEncoding("UTF-8");
                    if (filePath.startsWith("filemgr://")) {
                        //文件下载
                        //获取文件inputStream流
                        //OutputStream流输出：write、flush
                        //关闭流
                    }
                }
            }
        }
        return respResult;
    }

    //处理请求的body参数值
    private Map<String, String> convertFormDataBody(HttpServletRequest request) throws Exception {
        Map<String, String> formDataBody = new HashMap<>();
        System.out.println(request.getContentType());
        //请求form-data空
        if (request.getContentType() == null) {
            if (request.getParameterMap() != null) {
                request.getParameterMap().entrySet().stream().forEach(e -> {
                    formDataBody.put(e.getKey(), Stream.of(e.getValue()).collect(Collectors.joining(",")));
                });
            }
            //请求form-data有值
        } else if ("multipart/form-data".equals(request.getContentType())) {
            String formDataAll = new String(readInputStream(request.getInputStream()), "UTF-8");
            if (!StringUtils.isEmpty(formDataAll)) {
                String[] formDataArr = formDataAll.split("Content-Disposition");
                for (int i = 0; i < formDataArr.length; i++) {
                    String formDataTmp = formDataArr[i];
                    if (formDataTmp.contains("form-data;") && !formDataTmp.contains("filename=\"") && !formDataTmp.contains("Content-Type:")) {
                        String[] formDataTmpArr = formDataTmp.split("\r");
                        String name = StrUtil.subBetween(formDataTmpArr[0], "name=\"", "\"");
                        String value = StrUtil.trim(formDataTmpArr[formDataTmpArr.length - 3], 0);
                        formDataBody.put(name, value);
                    }
                }
            }
            //请求form-data有值和包含文件流
        } else if (request.getContentType().contains("multipart/form-data") && request.getContentType().contains("boundary")) {
            request.getParameterMap().entrySet().stream().forEach(e -> formDataBody.put(e.getKey(), Stream.of(e.getValue()).collect(Collectors.joining(","))));
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
