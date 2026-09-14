package com.hrh.servicearrange.dsl;

import java.util.ArrayList;
import java.util.List;

public class Group {
    /**
     * 类型：补偿强组合
     */
    public static final String TYPE_COMPENSATE = "group_compensate";
    /**
     * 强组合id
     */
    private String id;
    /**
     * 名称
     */
    private String name;
    /**
     * 类型
     */
    private String type;
    /**
     * 哪些节点属于本组合
     */
    private List<String> nodes = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getNodes() {
        return nodes;
    }

    public void setNodes(List<String> nodes) {
        this.nodes = nodes;
    }
}
