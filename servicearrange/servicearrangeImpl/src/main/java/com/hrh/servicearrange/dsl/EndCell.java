package com.hrh.servicearrange.dsl;


import com.hrh.servicearrange.parser.annotation.CellType;

public class EndCell extends Cell {

    private String cellType = CellType.END;
    private Data data;

    public class Data {
        private String contentType;
        private String outputsJsonSchema;
        private String valueJsonpathMapping;

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public String getOutputsJsonSchema() {
            return outputsJsonSchema;
        }

        public void setOutputsJsonSchema(String outputsJsonSchema) {
            this.outputsJsonSchema = outputsJsonSchema;
        }

        public String getValueJsonpathMapping() {
            return valueJsonpathMapping;
        }

        public void setValueJsonpathMapping(String valueJsonpathMapping) {
            this.valueJsonpathMapping = valueJsonpathMapping;
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
