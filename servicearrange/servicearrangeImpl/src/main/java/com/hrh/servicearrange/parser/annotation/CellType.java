package com.hrh.servicearrange.parser.annotation;

import java.lang.annotation.*;

/**
 * @author huangrenhui
 * @date 2022/3/31
 * @flow
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CellType {
    /**
     * 线类型
     */
    public static final String EDGE_COMMON = "edge_common";//普通线
    public static final String EDGE_LOOP = "edge_loop";//循环线
    public static final String EDGE_DECISION = "edge_decision";//决策线
    public static final String EDGE_COMPENSATE = "edge_compensate";//补偿线
    /**
     * 节点
     */
    public static final String START = "node_start";
    public static final String END = "node_end";

    /**
     * 全局参数节点
     */
    public static final String GLOBAL_DATA = "global_Data";
    /**
     * 补偿节点
     */
    public static final String OPERATOR_HTTP_COMPENSATE = "node_outer_httpCompensate";
    public static final String OPERATOR_DUBBO_COMPENSATE = "node_outer_dubboCompensate";
    public static final String OPERATOR_WEBSERVICE_COMPENSATE = "node_outer_webserviceCompensate";

    /**
     *  特殊节点
     */
    public static final String FUN_SHELL = "node_inner_execShell";
    public static final String FUN_JAR = "node_inner_execJar";
    public static final String FUN_PYTHON = "node_inner_execPython";
    /**
     * 内部节点
     */
    public static final String FUN_DATARESULT = "node_inner_dataresult";//数据结果节点
    public static final String FUN_DECISION = "node_inner_decision";//决策节点
    public static final String FUN_SLEEP = "node_inner_sleep";//睡眠节点
    public static final String FUN_DATAMAP = "node_inner_datamap";//数据映射节点
    public static final String FUN_TRANSFORM = "node_inner_transform2Obj";//规则节点
    public static final String FUN_COMPENSATE_DATAMAP = "node_inner_compensateDatamap";//补偿数据映射节点
    public static final String FUN_JSON2XML = "node_inner_convertJson2Xml";//
    public static final String FUN_XML2JSON = "node_inner_convertXml2Json";
    public static final String OPERATOR_HTTP = "node_outer_http";
    public static final String OPERATOR_DUBBO = "node_outer_dubbo";
    public static final String OPERATOR_WEBSERVICE = "node_outer_webservice";
}
