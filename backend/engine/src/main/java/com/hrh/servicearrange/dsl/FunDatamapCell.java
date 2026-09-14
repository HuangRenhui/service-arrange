package com.hrh.servicearrange.dsl;


import com.hrh.servicearrange.parser.annotation.CellType;

import java.util.List;

public class FunDatamapCell extends Cell {

    private String cellType = CellType.FUN_DATAMAP;
    private Data data;

    public class Data {
        private List<EndCell.Data> parentsOutputs;
        private ChildInput childInputs;

        public ChildInput getChildInputs() {
            return childInputs;
        }

        public void setChildInputs(ChildInput childInputs) {
            this.childInputs = childInputs;
        }

        public List<EndCell.Data> getParentsOutputs() {
            return parentsOutputs;
        }

        public void setParentsOutputs(List<EndCell.Data> parentsOutputs) {
            this.parentsOutputs = parentsOutputs;
        }

        public class ChildInput {
            private List<KeyValueWithJsonPathDto> reqPath;
            private List<KeyValueWithJsonPathDto> reqQuery;
            private List<KeyValueWithJsonPathDto> reqHeaders;
            private List<KeyValueWithJsonPathDto> reqBodyForm;
            private String reqBodyOther; //jsonSchema

            public List<KeyValueWithJsonPathDto> getReqPath() {
                return reqPath;
            }

            public void setReqPath(List<KeyValueWithJsonPathDto> reqPath) {
                this.reqPath = reqPath;
            }

            public List<KeyValueWithJsonPathDto> getReqQuery() {
                return reqQuery;
            }

            public void setReqQuery(List<KeyValueWithJsonPathDto> reqQuery) {
                this.reqQuery = reqQuery;
            }

            public List<KeyValueWithJsonPathDto> getReqHeaders() {
                return reqHeaders;
            }

            public void setReqHeaders(List<KeyValueWithJsonPathDto> reqHeaders) {
                this.reqHeaders = reqHeaders;
            }

            public List<KeyValueWithJsonPathDto> getReqBodyForm() {
                return reqBodyForm;
            }

            public void setReqBodyForm(List<KeyValueWithJsonPathDto> reqBodyForm) {
                this.reqBodyForm = reqBodyForm;
            }

            public String getReqBodyOther() {
                return reqBodyOther;
            }

            public void setReqBodyOther(String reqBodyOther) {
                this.reqBodyOther = reqBodyOther;
            }

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
