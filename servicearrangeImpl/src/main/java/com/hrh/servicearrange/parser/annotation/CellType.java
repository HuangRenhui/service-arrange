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
}
