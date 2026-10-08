package com.hrh.servicearrange.controller;

import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/instlog")
public class InstLogController {

    @Autowired
    private InstLogDao instLogDao;

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam String instId,
                                    @RequestParam(required = false) String nodeId) {
        if (StringUtils.isEmpty(nodeId)) {
            return ApiResponse.ok(instLogDao.findByInstIdOrderByCreateDateAsc(instId));
        }
        return ApiResponse.ok(instLogDao.findByInstIdAndNodeIdOrderByCreateDateAsc(instId, nodeId));
    }
}
