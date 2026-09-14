package com.hrh.servicearrange.utils;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/**
 * @author huangrenhui
 * @date 2022/4/1
 * @flow
 */
public class JsonSchemaUtil {
    public final static JSONConfig jsonConfig = JSONConfig.create().setIgnoreNullValue(false);


    /**
     * {
     * "type": "object",
     * "properties": {
     * "layer": {
     * "jsonpathMapping": "#staticParams_$layer",
     * "type": "string",
     * "value": "jsyd_slk"
     * },
     * "where": {
     * "jsonpathMapping": "#start_$where",
     * "type": "string",
     * "value": "xmmc='城镇分批次测试'"
     * },
     * "dbUrl": {
     * "jsonpathMapping": "#staticParams_$dbUrl",
     * "type": "string",
     * "value": "pgdb://192.168.20.237:5432/imap_dms_new/imap_gzt?username=sde&password=Southgis@5432"
     * }* 	}
     * }
     */
    public static JSONObject jsonSchema2JsonObject(String jsonSchema) {
        boolean isJsonObj = JSONUtil.isJsonObj(jsonSchema);
        if (!isJsonObj) {
            throw new RuntimeException("jsonSchema illegal! is'nt a jsonObject:" + jsonSchema);
        }
        JSONObject jsonObject = JSONUtil.parseObj(jsonSchema);
        String type = jsonObject.getStr("type");
        if (!"object".equals(type)) {
            throw new RuntimeException("this method is just support [object],current is:" + type);
        }
        JSONObject result = JSONUtil.createObj(jsonConfig);
        JSONObject properties = jsonObject.getJSONObject("properties");
        properties.keySet().stream().forEach(key -> {
            JSONObject o = properties.getJSONObject(key);
            String t = o.getStr("type");
            if ("array".equals(t)) {
                JSONArray next = jsonSchema2JsonArray(JSONUtil.toJsonStr(o, jsonConfig));
                result.set(key, next);
            } else if ("object".equals(t)) {
                if (!o.containsKey("properties") || o.getJSONObject("properties").size() <= 0) {
                    result.set(key, o.get("value"));
                } else {
                    JSONObject next = jsonSchema2JsonObject(JSONUtil.toJsonStr(o, jsonConfig));
                    result.set(key, next);
                }
            } else if ("number".equals(t)) {
                result.set(key, o.getLong("value"));
            } else if ("boolean".equals(t)) {
                result.set(key, o.getBool("value"));
            } else if ("string".equals(t)) {
                result.set(key, o.getStr("value"));
            } else {
                result.set(key, o.get("value"));
            }
        });
        return result;
    }

    public static JSONArray jsonSchema2JsonArray(String jsonSchema) {
        boolean isJsonObj = JSONUtil.isJson(jsonSchema);
        if (!isJsonObj) {
            throw new RuntimeException("jsonschema illegal!is'nt a jsonObject：" + jsonSchema);
        }
        JSONObject jsonObject = JSONUtil.parseObj(jsonSchema);
        String type = jsonObject.getStr("type");
        if (!"array".equals(type)) {
            throw new RuntimeException("this method is just support [array],current is" + type);
        }
        JSONArray result = JSONUtil.createArray(jsonConfig);
        JSONObject items = jsonObject.getJSONObject("items");
        if (items == null || items.size() < 0 || !JSONUtil.isJsonArray(items.toString())) {
            JSONArray value = jsonObject.getJSONArray("value");
            return value;
        } else {
            items.keySet().stream().forEach(key -> {
                JSONObject keyObj = items.getJSONObject(key);
                String keyType = keyObj.getStr("type");
                if ("array".equals(keyType)) {
                    JSONArray next = jsonSchema2JsonArray(JSONUtil.toJsonStr(keyObj, jsonConfig));
                    result.add(next);
                } else if ("object".equals(type)) {
                    JSONObject next = jsonSchema2JsonObject(JSONUtil.toJsonStr(keyObj, jsonConfig));
                    result.add(next);
                } else {
                    result.add(keyObj.get("value"));
                }
            });
        }
        return result;
    }
    public static void main(String[] args) {
        String xx = "{\"type\":\"object\",\"properties\":{\"code\":{\"jsonpathMapping\":\"\",\"defaultValue\":\"服务编排规则\",\"type\":\"string\",\"value\":null},\"args\":{\"type\":\"object\",\"value\":null,\"properties\":{\"length\":{\"jsonpathMapping\":\"#pno_a757378a-889c-4114-b0c1-292851a2bcba$length\",\"type\":\"number\",\"value\":200},\"width\":{\"jsonpathMapping\":\"#pno_a757378a-889c-4114-b0c1-292851a2bcba$width\",\"type\":\"number\",\"value\":50}}}}}";
        JSONObject r = jsonSchema2JsonObject(xx);
        System.out.println(JSONUtil.toJsonStr(r, jsonConfig));
    }
}
