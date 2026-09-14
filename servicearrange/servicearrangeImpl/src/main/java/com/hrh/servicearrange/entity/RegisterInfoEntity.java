package com.hrh.servicearrange.entity;

import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * @author hrh
 */

@Data
@Document(collection = RegisterInfoEntity.TABLE_NAME)
public class RegisterInfoEntity extends BaseEntity {

    private static final long serialVersionUID = -1494149505348040159L;
    public static final String TABLE_NAME = "REGISTER_INFO";

    /**
     * 类型格式，http、rpc等
     */
    private String type;
    /**
     * api名称
     */
    private String serviceName;
    /**
     * 源路径
     */
    private String serviceUrl;
    /**
     * 请求方法，post、get等
     */
    private String requestMethod;
    /**
     * 描述
     */
    private String description;
    /**
     * 目录id
     */
    private String catalogueId;


    /**
     * 入参headers，添加Content-Type等信息
     */
    private String reqHeaders;

    /**
     * 入参query，urlpath的参数
     */
    private String reqQuery;


    /**
     * 入参body的格式，比如有json、text、xml
     */
    private String reqBodyType;

    /**
     * 入参开启jsonschema：true-开启，false-关闭，默认必须开启
     */
    private Boolean reqBodyIsJsonSchema;

    /**
     * 入参body的格式是json、开启了json-schema后组成的数据
     */
    private String reqBodyData;
    /**
     * 出参开启jsonschema
     */
    private Boolean resBodyIsJsonSchema;
    /**
     * 出参类型，比如有json、二进制流（HTML、图片、xml文件、JSON文件）
     */
    private String resBodyType;
    /**
     * 如果出参类型是json，这个属性值是json-schema的数据，其他类型忽略该属性
     */
    private String resBodyData;

    /**
     * mock测试参数数据
     */
    private String mockParam;
    /**
     * 超时设置
     */
    private String timeOut;


    /**
     * 服务编排校验 需要的checkJsonSchema
     */
    private String checkJsonSchema;

    /**
     * htmlPath
     */
    private String htmlPath;
    /**
     * sheelFtpPath
     */
    private String sheelFtpPath;


}
