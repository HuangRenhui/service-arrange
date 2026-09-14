package com.hrh.servicearrange.dsl;


import com.hrh.servicearrange.parser.annotation.CellType;

import java.util.List;

//决策线
public class EdgeDecisionCell extends EdgeCommonCell {

    private String cellType = CellType.EDGE_DECISION;
    private Data data;

    public class ConditionData {
        private String eventContent;
        private String type;
        private String condition;
        private String logic;
        private String Rjsonpath;

        public String getEventContent() {
            return eventContent;
        }

        public void setEventContent(String eventContent) {
            this.eventContent = eventContent;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public String getLogic() {
            return logic;
        }

        public void setLogic(String logic) {
            this.logic = logic;
        }

        public String getRjsonpath() {
            return Rjsonpath;
        }

        public void setRjsonpath(String rjsonpath) {
            Rjsonpath = rjsonpath;
        }
    }

    public class Data {
        private List<ConditionData> conditionData;
        private String jsonpathElexpression; //判断表达  () <= >= != == || && ，由计算表达式的R[]的值来判断，R0>100 || R1<90 ,R0表示R[]中坐标为R[0]的表达式
        private String[] R; //计算表达式 集合，操作符 ()+-*/ 及字符串拼接 ||
        private String[] L; //计算表达式 集合，操作符 ()+-*/ 及字符串拼接 ||

        public String getJsonpathElexpression() {
            return jsonpathElexpression;
        }

        public void setJsonpathElexpression(String jsonpathElexpression) {
            this.jsonpathElexpression = jsonpathElexpression;
        }

        public String[] getR() {
            return R;
        }

        public void setR(String[] r) {
            R = r;
        }

        public String[] getL() {
            return L;
        }

        public void setL(String[] l) {
            L = l;
        }

        public List<ConditionData> getConditionData() {
            return conditionData;
        }

        public void setConditionData(List<ConditionData> conditionData) {
            this.conditionData = conditionData;
        }

    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    public String getCellType() {
        return cellType;
    }

    public void setCellType(String cellType) {
        this.cellType = cellType;
    }

}
