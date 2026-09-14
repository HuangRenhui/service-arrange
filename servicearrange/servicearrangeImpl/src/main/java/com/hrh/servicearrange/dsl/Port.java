package com.hrh.servicearrange.dsl;

import java.util.List;

/**
 * 桩点：前端连接线用的
 */
public class Port {
    /**
     * 线id集合
     */
    private List<Item> items;

    public class Item {
        /**
         * 线id
         */
        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }


    public List<Item> getItems() {
        return items;
    }


    public void setItems(List<Item> items) {
        this.items = items;
    }
}
