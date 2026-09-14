package com.hrh.servicearrange.controller;

import cn.hutool.core.io.FileUtil;
import com.hrh.servicearrange.vo.InstRunParamsVo;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.support.StandardMultipartHttpServletRequest;

import javax.servlet.http.HttpServletResponse;

/**
 * @author huangrenhui
 * @date 2022/3/31
 * @flow
 */
@RestController
@RequestMapping("/inst")
public class InstController {
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
        return null;
    }
}
