package com.hrh.servicearrange.utils;

import cn.hutool.json.JSONConfig;

/**
 * @author huangrenhui
 * @date 2022/4/1
 * @flow
 */
public class JsonSchemaUtil {
    public final static JSONConfig jsonConfig = JSONConfig.create().setIgnoreNullValue(false);

    public static void main(String[] args) {
        System.out.println(jsonConfig);
    }
}
