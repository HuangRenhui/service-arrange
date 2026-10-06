package com.hrh.servicearrange.entity;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = Plan.TABLE_NAME)
public class Plan extends BaseEntity {

    public static final String TABLE_NAME = "SERVEA_PLAN";

    @Field("name")
    private String name;
    @Field("dsl")
    private String dsl;
    @Field("remark")
    private String remark;
    @Field("version")
    private Integer version = 1;
    /**
     * 生命周期：DRAFT 可改不可对外跑（门禁开启时）；PUBLISHED 可运行；DISABLED 停用。
     */
    @Field("status")
    private String status = STATUS_DRAFT;
    @Field("env")
    private String env;
    @Field("owner")
    private String owner;
    @Field("defaultTimeoutMs")
    private Long defaultTimeoutMs;

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DISABLED = "DISABLED";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDsl() {
        return dsl;
    }

    public void setDsl(String dsl) {
        this.dsl = dsl;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(Long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }
}
