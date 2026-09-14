package com.hrh.servicearrange.dsl;

import com.hrh.servicearrange.parser.annotation.CellType;

import java.util.List;

/**
 * @author huangrenhui
 * @date 2022/3/31
 * @flow 节点信息
 */
public class Cell {
    /**
     * 节点id
     */
    private String id;
    /**
     * 节点名称
     */
    private String name;
    /**
     * 出入参数据
     */
    private Object data;
    /**
     * 线的入度
     */
    private EdgeEndpoint source;
    /**
     * 线的出度
     */
    private EdgeEndpoint target;
    /**
     * 节点桩点：前端连线用的
     */
    private Port ports;
    /**
     * 强组合节点id集合
     */
    private List<String> groupIds;
    /**
     * 节点类型，默认为线
     */
    private String cellType = CellType.EDGE_COMMON;

    public class EdgeEndpoint {
        //节点id
        private String cell;
        //桩点
        private String port;

        public String getCell() {
            return cell;
        }

        public void setCell(String cell) {
            this.cell = cell;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public EdgeEndpoint getSource() {
        return source;
    }

    public void setSource(EdgeEndpoint source) {
        this.source = source;
    }

    public EdgeEndpoint getTarget() {
        return target;
    }

    public void setTarget(EdgeEndpoint target) {
        this.target = target;
    }

    public Port getPorts() {
        return ports;
    }

    public void setPorts(Port ports) {
        this.ports = ports;
    }

    public List<String> getGroupIds() {
        return groupIds;
    }

    public void setGroupIds(List<String> groupIds) {
        this.groupIds = groupIds;
    }

    public String getCellType() {
        return cellType;
    }

    public void setCellType(String cellType) {
        this.cellType = cellType;
    }
}
