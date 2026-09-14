package com.hrh.servicearrange.controller;


import com.hrh.servicearrange.entity.RegisterInfoEntity;
import com.hrh.servicearrange.service.IRegisterInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author huangrenhui
 * @date 2021/11/15
 * @apiDefine 服务注册
 * 算子注册：算子增删查改
 */
@Slf4j
@RestController
@RequestMapping("/registerOperator")
public class RegisterInfoController {

    @Autowired
    IRegisterInfoService registerInfoService;


    /**
     * @api {POST} ./registerOperator/save
     * @apiGroup servicearrange
     * @apiName save
     * @apiVersion 2.0.0
     * @apiDescription 算子注册，存入算子包含的信息。
     * @apiParam{ "id": "125",
     * "serviceName": "name",
     * "category": "1",
     * "description": "描述",
     * "serviceUrl": "www.baidu.com",
     * "requestMethod": "36",
     * "inParam": "in",
     * "outParam": "out" ,
     * "rollBackId": "-1",
     * "testId": "1"
     * }
     * @apiSuccess String 返回"success" 表示成功
     * @apiSuccessExample {json} 调用示例
     * POST /servicearrange/registerOperator/save
     * ========
     * HTTP/1.1 200 OK
     * <p>
     * 返回结果示例:"success"
     * @apiSampleRequest http://localhost:8082/servicearrange/registerOperator/save
     */
    @PostMapping(value = "/save")
    public String save(@RequestBody RegisterInfoEntity registerInfoEntity) {
        return registerInfoService.save(registerInfoEntity);

    }


    /**
     * @api {POST} ./registerOperator/delete
     * @apiGroup servicearrange
     * @apiName delete
     * @apiVersion 2.0.0
     * @apiDescription 算子删除，删除指定算子的信息
     * @apiParam ["id","id",....]
     * @apiSuccess String 返回"success" 表示成功
     * @apiSuccessExample {json} 调用示例
     * POST /servicearrange/registerOperator/delete
     * ========
     * HTTP/1.1 200 OK
     * <p>
     * 返回结果示例:"json_list_object"
     * @apiSampleRequest http://192.168.10.29:8301/servicearrange/registerOperator/delete
     */

    @PostMapping(value = "/delete", produces = "application/json;charset=UTF-8")
    public String delete(@RequestBody List<String> list) {
        return registerInfoService.delete(list);
    }

    /**
     * @api {GET} ./registerOperator/findById/{id}
     * @apiGroup servicearrange
     * @apiName findById
     * @apiVersion 2.0.0
     * @apiDescription 算子查询，查询指定算子的信息
     * @apiParam {id}
     * @apiSuccess RegisterInfoEntity 返回查询出来的实体对象
     * @apiSuccessExample {json} 调用示例
     * GET /servicearrange/registerOperator/findById/2
     * ========
     * HTTP/1.1 200 OK
     * <p>
     * 返回结果示例:
     * {
     * "id": "12",
     * "serviceName": "asd",
     * "category": "2",
     * "description": "4",
     * "serviceUrl": "5",
     * "requestMethod": "6",
     * "inParam": "7",
     * "outParam": "8",
     * "rollBackId": "1",
     * "testId": "11",
     * "codeFilename": "12",
     * "releaseTime": "2021-11-19 14:16:26",
     * "timeOut": 14,
     * "requestDataFormat": null,
     * "catalogueId": "3"
     * }
     * @apiSampleRequest http://192.168.10.29:8301/servicearrange/registerOperator/findById/2
     */

    @RequestMapping(value = "/findById")
    public Object findById(String id) {
        return registerInfoService.findById(id);
    }

}
