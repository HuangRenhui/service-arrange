package com.hrh.servicearrange.parser;

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
    public static final String EDGE_COMMON = "edge_common";
}
