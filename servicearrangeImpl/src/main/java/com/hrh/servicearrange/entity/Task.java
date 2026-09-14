package com.hrh.servicearrange.entity;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;

/**
 * @author huangrenhui
 * @date 2022/3/31
 * @flow 每个节点生成对应的任务
 */
@Document(collection = Task.TABLE_NAME)
public class Task extends BaseEntity implements Serializable {

    public static final String TABLE_NAME = "SERVEA_TASK";
    private static final long serialVersionUID = -8574270025529141305L;
    /**
     * 任务状态
     */
    public static final String STATE_WAITE = "WAITE";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_SUCCESS = "SUCCESS";
    public static final String STATE_SKIP = "SKIP";
    public static final String STATE_FAIL = "FAIL";
    /**
     * 实例id
     */
    @Field("instId")
    private String instId;
    /**
     * 模型id
     */
    @Field("planId")
    private String planId;
    /**
     * 模型名称
     */
    @Field("planName")
    private String planName;
    /**
     * 节点id
     */
    @Field("nodeId")
    private String nodeId;
    /**
     * 节点名称
     */
    @Field("nodeName")
    private String nodeName;
    /**
     * 任务状态
     */
    @Field("state")
    private String state;
    /**
     * 节点类型
     */
    @Field("type")
    private String type;
    /**
     * 是否是进行调试，默认false
     */
    @Field("debug")
    private Boolean debug = false;

    /**
     * 任务入参
     */
    @Field("inputs")
    private String inputs;
    /**
     * 回滚状态
     */
    @Field("rollbackState")
    private String rollbackState;
    /**
     * 出参：json、二进制、单值
     */
    @Field("outputs")
    private Result outputs = new Result();
    /**
     * 重试规则
     */
    @Field("retryRules")
    private RetryRules retryRules = new RetryRules();
    /**
     * mq发状态 ack时间
     */
    @Field("stateNackTimes")
    private Integer stateNackTimes = 0;
    /**
     * mq发任务运行 ack时间
     */
    @Field("runNackTimes")
    private Integer runNackTimes = 0;
    /**
     * 总开始时间
     */
    @Field("startDate")
    private Date startDate;
    /**
     * 总结束时间
     */
    @Field("endDate")
    private Date endDate;
    /**
     * 执行器开始时间
     */
    @Field("executorStartDate")
    private Date executorStartDate;
    /**
     * 执行器结束时间
     */
    @Field("executorEndDate")
    private Date executorEndDate;
    /**
     * 循环次数，每次循环将建立一个新的任务，也就是说一个节点，将有可能创建多个任务
     * 格式 循环线id+"|"+次数，无循环时 null
     */
    @Field("loopTimes")
    private String loopTimes = "0";


    public static class RetryRules {
        /**
         * 重试次数
         */
        @Field("times")
        private Integer times;
        /**
         * 已重试次数
         */
        @Field("doneTimes")
        private Integer doneTimes = 0;
        /**
         * 重试间隔时间：单位/秒
         */
        @Field("delaySecondTimes")
        private Long delaySecondTimes;


        public Integer getTimes() {
            return times;
        }

        public void setTimes(Integer times) {
            this.times = times;
        }

        public Long getDelaySecondTimes() {
            return delaySecondTimes;
        }

        public void setDelaySecondTimes(Long delaySecondTimes) {
            this.delaySecondTimes = delaySecondTimes;
        }

        public Integer getDoneTimes() {
            return doneTimes;
        }

        public void setDoneTimes(Integer doneTimes) {
            this.doneTimes = doneTimes;
        }
    }

    public static class Result {
        /**
         * 出参类型
         */
        @Field("contentType")
        private String contentType;
        /**
         * 出参类型映射关系
         */
        @Field("jsonSchema")
        private String jsonSchema;
        /**
         * 出参值
         */
        @Field("value")
        private String value;
        /**
         * 出参头信息
         */
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

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public Result() {
            // TODO Auto-generated constructor stub
        }

        public Result(String contentType, String path, String value) {
            super();
            this.contentType = contentType;
            this.value = value;
        }

        public Result(String value) {
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

    public String getInstId() {
        return instId;
    }

    public void setInstId(String instId) {
        this.instId = instId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Boolean getDebug() {
        return debug;
    }

    public void setDebug(Boolean debug) {
        this.debug = debug;
    }

    public String getInputs() {
        return inputs;
    }

    public void setInputs(String inputs) {
        this.inputs = inputs;
    }

    public String getRollbackState() {
        return rollbackState;
    }

    public void setRollbackState(String rollbackState) {
        this.rollbackState = rollbackState;
    }

    public Result getOutputs() {
        return outputs;
    }

    public void setOutputs(Result outputs) {
        this.outputs = outputs;
    }

    public RetryRules getRetryRules() {
        return retryRules;
    }

    public void setRetryRules(RetryRules retryRules) {
        this.retryRules = retryRules;
    }

    public Integer getStateNackTimes() {
        return stateNackTimes;
    }

    public void setStateNackTimes(Integer stateNackTimes) {
        this.stateNackTimes = stateNackTimes;
    }

    public Integer getRunNackTimes() {
        return runNackTimes;
    }

    public void setRunNackTimes(Integer runNackTimes) {
        this.runNackTimes = runNackTimes;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public Date getExecutorStartDate() {
        return executorStartDate;
    }

    public void setExecutorStartDate(Date executorStartDate) {
        this.executorStartDate = executorStartDate;
    }

    public Date getExecutorEndDate() {
        return executorEndDate;
    }

    public void setExecutorEndDate(Date executorEndDate) {
        this.executorEndDate = executorEndDate;
    }

    public String getLoopTimes() {
        return loopTimes;
    }

    public void setLoopTimes(String loopTimes) {
        this.loopTimes = loopTimes;
    }
}
