package com.hrh.servicearrange.dsl;


import com.hrh.servicearrange.parser.annotation.CellType;
import com.hrh.servicearrange.parser.annotation.LoopType;

import java.util.ArrayList;
import java.util.List;
//循环线
public class EdgeLoopCell extends EdgeCommonCell {

    private String cellType = CellType.EDGE_LOOP;
    private Data data;

    //循环判断体
    @lombok.Data
    public class CycleInfo {
        /**
         * {
         * "jsonpathSelect": "#dynamicParams_$idx",
         * "rule": "+",
         * "type": "doWhile",
         * "stepLength": 1
         * }
         */
        //判断点
        private String jsonpathSelect;
        //规则
        private String rule;
        //类型
        private String type;
        //步骤
        private String stepLength;

    }

    public class Data {
        /**
         * 循环模式
         * while_do模式只能接收动态全局参数{@link DSL#getDynamicGlobalParameters()} 用于判断
         * do_while模式可接收动态全局参数{@link DSL#getDynamicGlobalParameters()}与线输出点的结果参数 用于判断
         */
        private String loopType = LoopType.DO_WHILE;

        private List<FunTransform2ObjCell.Data.TransformRule> transformRules = new ArrayList<>();//数据转换处理

        private String jsonpathElexpression; //判断表达  () <= >= != == || && ，由计算表达式的R[]的值来判断，R0>100 || R1<90 ,R0表示R[]中坐标为R[0]的表达式
        private String[] R; //计算表达式 集合，操作符 ()+-*/ 及字符串拼接 ||
        private String[] L; //长度
        private CycleInfo cycleInfo;

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

        public String getLoopType() {
            return loopType;
        }

        public void setLoopType(String loopType) {
            this.loopType = loopType;
        }

        public List<FunTransform2ObjCell.Data.TransformRule> getTransformRules() {
            return transformRules;
        }

        public void setTransformRules(List<FunTransform2ObjCell.Data.TransformRule> transformRules) {
            this.transformRules = transformRules;
        }

        public CycleInfo getCycleInfo() {
            return cycleInfo;
        }

        public void setCycleInfo(CycleInfo cycleInfo) {
            this.cycleInfo = cycleInfo;
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
