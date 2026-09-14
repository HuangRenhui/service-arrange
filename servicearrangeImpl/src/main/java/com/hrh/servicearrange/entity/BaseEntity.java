package com.hrh.servicearrange.entity;

/**
 * @author huangrenhui
 * @flow
 */

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Date;

public class BaseEntity {
    @Id
    @Field("id")
    protected String id;
    @Field("createDate")
    protected Date createDate;
    @Field("modifyDate")
    protected Date modifyDate;
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public Date getCreateDate() {
        return createDate;
    }
    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }
    public Date getModifyDate() {
        return modifyDate;
    }
    public void setModifyDate(Date modifyDate) {
        this.modifyDate = modifyDate;
    }
}
