package com.hrh.servicearrange.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.parser.DslParser;
import com.hrh.servicearrange.utils.JsonSchemaUtil;
import com.hrh.servicearrange.vo.InstRunParamsVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.StandardMultipartHttpServletRequest;

import javax.servlet.http.HttpServletResponse;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

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

    /**
     * @param planId   模型id
     * @param request  参数
     * @param response
     * @return
     * @throws Exception
     */
    @PostMapping("/run/or/getResult/{planId}")
    public Object run(@PathVariable(name = "planId") String planId, StandardMultipartHttpServletRequest request,
                      HttpServletResponse response) throws Exception {
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
        Enumeration<String> parameterNames = request.getParameterNames();
        while (parameterNames.hasMoreElements()) {
            String name = parameterNames.nextElement();
            String value = request.getParameter(name);
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
        }
        instRunParamsVo.setInstName(paramObj.getStr("servea_instName"));
        instRunParamsVo.setSync(paramObj.containsKey("servea_sync") ? paramObj.getBool("servea_sync") : true);
        instRunParamsVo.setOptType(paramObj.containsKey("servea_optType") ? paramObj.getStr("servea_optType") : "run");
        //处理请求的文件
        MultiValueMap<String, MultipartFile> multiFiles = request.getMultiFileMap();
        multiFiles.keySet().stream().forEach(fileKey -> {
            MultipartFile mFile = multiFiles.getFirst(fileKey);
            String originalFilename = mFile.getOriginalFilename();
            int size = Long.valueOf(mFile.getSize()).intValue();
            //进行文件保存
            System.out.println(originalFilename + ":" + size);
            paramObj.set(fileKey, "path");
        });
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
        return null;
    }
}
