package com.hrh.servicearrange.dsl;

import com.hrh.servicearrange.parser.annotation.CellType;

import java.util.List;

/**
 * @author huangrenhui
 * @date 2022/4/8
 * @flow 规则转换节点
 */
public class FunTransform2ObjCell extends Cell {
    private String cellType = CellType.FUN_TRANSFORM;
    private Data data;

    public class Data {
        private List<TransformRule> transformRuleList;

        public class TransformRule {
            private String key; //新obj 对应的key
            private String keyType;//key类型 string number ...
            private String type; //规则类型 默认：default 计算：compute； 字符串拼接：str_append； 转换为数组：list_convert
            private String jsonpathMapping; //表达式
            private Object defaultValue; //默认值

            public String getKey() {
                return key;
            }

            public void setKey(String key) {
                this.key = key;
            }

            public String getKeyType() {
                return keyType;
            }

            public void setKeyType(String keyType) {
                this.keyType = keyType;
            }

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
            }

            public String getJsonpathMapping() {
                return jsonpathMapping;
            }

            public void setJsonpathMapping(String jsonpathMapping) {
                this.jsonpathMapping = jsonpathMapping;
            }

            public Object getDefaultValue() {
                return defaultValue;
            }

            public void setDefaultValue(Object defaultValue) {
                this.defaultValue = defaultValue;
            }
        }

        public List<TransformRule> getTransformRuleList() {
            return transformRuleList;
        }

        public void setTransformRuleList(List<TransformRule> transformRuleList) {
            this.transformRuleList = transformRuleList;
        }
    }

    @Override
    public String getCellType() {
        return cellType;
    }

    @Override
    public void setCellType(String cellType) {
        this.cellType = cellType;
    }

    @Override
    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }
}
