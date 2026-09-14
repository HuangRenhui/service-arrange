package com.hrh.servicearrange.parser.annotation;

import java.lang.annotation.*;

/**
 * 任务类型
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TaskType {
	
    String shape();
    
    public static final String suffix = "_task";
    
    public static final String START = CellType.START+suffix;
    public static final String END = CellType.END+suffix;
    
    public static final String EDGE_COMMON = CellType.EDGE_COMMON+suffix;
    public static final String EDGE_LOOP = CellType.EDGE_LOOP+suffix;
    public static final String EDGE_DECISION = CellType.EDGE_DECISION+suffix;
    public static final String EDGE_COMPENSATE = CellType.EDGE_COMPENSATE+suffix;
    
//    public static final String FUN_DATARESULT = CellType.FUN_DATARESULT+suffix;
//    public static final String FUN_DECISION = CellType.FUN_DECISION+suffix;
//    public static final String FUN_SLEEP = CellType.FUN_SLEEP+suffix;
//    public static final String FUN_DATAMAP = CellType.FUN_DATAMAP+suffix;
//    public static final String FUN_TRANSFORM = CellType.FUN_TRANSFORM+suffix;
//    public static final String FUN_COMPENSATE_DATAMAP = CellType.FUN_COMPENSATE_DATAMAP+suffix;
//    public static final String FUN_JSON2XML= CellType.FUN_JSON2XML+suffix;
//    public static final String FUN_XML2JSON= CellType.FUN_XML2JSON+suffix;
//
//    public static final String FUN_SHELL = CellType.FUN_SHELL+suffix;
//    public static final String FUN_JAR = CellType.FUN_JAR+suffix;
//    public static final String FUN_PYTHON= CellType.FUN_PYTHON+suffix;
//
//    public static final String OPERATOR_HTTP = CellType.OPERATOR_HTTP+suffix;
//    public static final String OPERATOR_HTTP_COMPENSATE = CellType.OPERATOR_HTTP_COMPENSATE+suffix;
//    public static final String OPERATOR_DUBBO = CellType.OPERATOR_DUBBO+suffix;
//    public static final String OPERATOR_DUBBO_COMPENSATE = CellType.OPERATOR_DUBBO_COMPENSATE+suffix;
//    public static final String OPERATOR_WEBSERVICE = CellType.OPERATOR_WEBSERVICE+suffix;
//    public static final String OPERATOR_WEBSERVICE_COMPENSATE = CellType.OPERATOR_WEBSERVICE_COMPENSATE+suffix;
//
//
//    public static final String STR_APPEND = CellType.STR_APPEND+suffix;
//    public static final String LIST_CONVERT = CellType.LIST_CONVERT+suffix;


}
