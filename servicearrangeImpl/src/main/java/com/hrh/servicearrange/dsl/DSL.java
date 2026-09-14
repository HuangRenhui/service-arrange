package com.hrh.servicearrange.dsl;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * dsl解析成对应的节点集合、强组合集合
 */
@Data
public class DSL {
    private List<Cell> cells;
    private List<Group> groups;
    /**
     * 可在运行过程中增加全局参数，并能操作全局参数
     */
    private List<KeyValueDto> dynamicGlobalParameters = new ArrayList<>();
}
