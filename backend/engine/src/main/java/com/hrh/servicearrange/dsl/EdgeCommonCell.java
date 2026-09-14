package com.hrh.servicearrange.dsl;


import com.hrh.servicearrange.parser.annotation.CellType;

public class EdgeCommonCell extends Cell {

    private String cellType = CellType.EDGE_COMMON;

    public String getCellType() {
        return cellType;
    }

    public void setCellType(String cellType) {
        this.cellType = cellType;
    }
}
