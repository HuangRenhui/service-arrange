package com.hrh.servicearrange.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Set;

@Data
public class NodeLoopInfo implements Serializable {

    private static final long serialVersionUID = 6881836080695222389L;
    /**
     * 循环线id
     */
    private String loopEdgeId;
    /**
     * 循环线之间节点id
     */
    private Set<String> idsBetweenLoopEdge;
    /**
     * 已经循环了多少次
     */
    private Integer times = 0;


    public NodeLoopInfo(String loopEdgeId, Set<String> idsBetweenLoopEdge, Integer times) {
        super();
        this.loopEdgeId = loopEdgeId;
        this.idsBetweenLoopEdge = idsBetweenLoopEdge;
        this.times = times;
    }


    public NodeLoopInfo() {
        super();
    }

}