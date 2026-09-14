package com.hrh.servicearrange.vo;

import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Map;

/**
 * @author huangrenhui
 * @flow 实例运行返回结果封装
 */
@Data
public class InstRunResponseVo {
    //实例id
    private String id;
    //实例状态:WAITE SUCCESS FAIL SUSPEND RESUME
    private String state;
    //json返回，文件返回不在此返回
    private Result outputs = new Result();

    public static class Result {
        private String contentType;
        @Field("jsonSchema")
        private String jsonSchema;
        @Field("value")
        private Object value;
        @Field("headerParams")
        private Map<String, String> headerParams;

        public String getContentType() {
            return contentType;
        }

        public String getJsonSchema() {
            return jsonSchema;
        }

        public void setJsonSchema(String jsonSchema) {
            this.jsonSchema = jsonSchema;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }

        public Result() {
            // TODO Auto-generated constructor stub
        }

        public Result(String contentType, String path, Object value) {
            super();
            this.contentType = contentType;
            this.value = value;
        }

        public Result(Object value) {
            super();
            this.value = value;
        }

        public Map<String, String> getHeaderParams() {
            return headerParams;
        }

        public void setHeaderParams(Map<String, String> headerParams) {
            this.headerParams = headerParams;
        }
    }
}
