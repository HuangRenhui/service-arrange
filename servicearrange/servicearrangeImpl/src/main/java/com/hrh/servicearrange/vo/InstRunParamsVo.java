package com.hrh.servicearrange.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * @author huangrenhui
 * @date 2022/3/31
 * @flow
 */
@Data
public class InstRunParamsVo {
    //实例名称
    private String instName;
    //dsl描述
    private String dsl;
    //操作类型 run:运行  debug:调试运行 get_result:获取结果
    private String optType;
    //true-同步；false-异步
    private Boolean sync;
    //不进行调试停止的节点
    private List<String> debugStopNodes = new ArrayList<>();
}
