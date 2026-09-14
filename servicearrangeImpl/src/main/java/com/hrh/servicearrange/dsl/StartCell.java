package com.hrh.servicearrange.dsl;


import com.hrh.servicearrange.parser.annotation.CellType;

import java.util.List;

/**
 * 开始节点
 */
public class StartCell extends Cell {

    private String cellType = CellType.START;
    /**
     * 数据，可参考hrh_dslDemo1.json
     */
    private Data data;

    public class Data {
        /**
         * jsonshcema
         */
        private String inputsJsonSchema;
        /**
         * 全局变量
         */
        private List<KeyValueDto> globals;

        public List<KeyValueDto> getGlobals() {
            return globals;
        }

        public void setGlobals(List<KeyValueDto> globals) {
            this.globals = globals;
        }

        public String getInputsJsonSchema() {
            return inputsJsonSchema;
        }

        public void setInputsJsonSchema(String inputsJsonSchema) {
            this.inputsJsonSchema = inputsJsonSchema;
        }
    }

    public class GlobalParams {
        private String name;
        private String key;
        private Object value;
        private String valueType;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }

        public String getValueType() {
            return valueType;
        }

        public void setValueType(String valueType) {
            this.valueType = valueType;
        }
    }

    public String getCellType() {
        return cellType;
    }

    public void setCellType(String cellType) {
        this.cellType = cellType;
    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

}
