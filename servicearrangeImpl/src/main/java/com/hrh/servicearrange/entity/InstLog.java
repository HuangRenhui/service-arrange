package com.hrh.servicearrange.entity;

import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.io.Serializable;
import java.util.Date;

@Document(collection = InstLog.TABLE_NAME)
public class InstLog extends BaseEntity implements Serializable {
    //日志级别
    @Transient
    public static final String LEVEL_ERRO = "ERRO";
    @Transient
    public static final String LEVEL_INFO = "INFO";
    @Transient
    public static final String LEVEL_WARN = "WARN";
    @Transient
    public static final String TABLE_NAME = "SERVEA_INST_LOG";
    @Transient
    private static final long serialVersionUID = 3148662950018776428L;
    //实例id
    @Field("instId")
    private String instId;
    //模型id
    @Field("planId")
    private String planId;
    //节点id
    @Field("nodeId")
    private String nodeId;
    //任务id
    @Field("taskId")
    private String taskId;
    //日志级别
    @Field("level")
    private String level = LEVEL_INFO;
    //日志具体信息
    @Field("message")
    private String message;

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

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public Date getCreateDate() {
        return this.createDate;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public InstLog() {
        this.createDate = new Date();
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public InstLog(String instId, String planId, String nodeId, String taskId, String level, String message) {
        super();
        this.createDate = new Date();
        this.instId = instId;
        this.planId = planId;
        this.nodeId = nodeId;
        this.taskId = taskId;
        this.message = message;
        this.level = level;
    }

    public InstLog(String instId, String planId, String nodeId, String taskId, String level) {
        super();
        this.createDate = new Date();
        this.instId = instId;
        this.planId = planId;
        this.nodeId = nodeId;
        this.taskId = taskId;
        this.level = level;
    }
}