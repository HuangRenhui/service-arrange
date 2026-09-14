package com.hrh.servicearrange.dsl;

/**
 * 键值对
 */
public class KeyValueDto {
    private String name;
    private String key;
    private String type;
    private Object value;
    private Object defaultValue;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public KeyValueDto() {
    }

    public KeyValueDto(String name, String key, String type, Object value, Object defaultValue) {
        super();
        this.name = name;
        this.key = key;
        this.type = type;
        this.value = value;
        this.defaultValue = defaultValue;
    }
}
