package com.jqgg.glm53.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 在指定数据库表上新增字段的请求体。
 */
public class TableColumnForm {

    @NotBlank(message = "请填写字段名")
    @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]{0,63}$",
            message = "字段名需以字母或下划线开头，可包含字母、数字和下划线（1-64 位）")
    private String fieldName;

    /** VARCHAR / TEXT / INT / BIGINT / DECIMAL / DATE / DATETIME / BOOLEAN */
    @NotBlank(message = "请选择字段类型")
    private String type;

    private Integer length;

    private Integer decimals;

    @Size(max = 500, message = "字段注释不能超过 500 字")
    private String comment;

    @Size(max = 500, message = "默认值不能超过 500 字")
    private String defaultValue;

    /** 是否允许 NULL，默认允许 */
    private Boolean nullable;

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getLength() {
        return length;
    }

    public void setLength(Integer length) {
        this.length = length;
    }

    public Integer getDecimals() {
        return decimals;
    }

    public void setDecimals(Integer decimals) {
        this.decimals = decimals;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Boolean getNullable() {
        return nullable;
    }

    public void setNullable(Boolean nullable) {
        this.nullable = nullable;
    }
}
