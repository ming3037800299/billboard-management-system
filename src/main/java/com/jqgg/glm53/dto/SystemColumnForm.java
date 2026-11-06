package com.jqgg.glm53.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改系统字段（billboard 核心列）的请求体。
 * 只允许在兼容的业务类型族内调整类型 / 长度 / 精度 / 可空性，字段名不可修改。
 */
public class SystemColumnForm {

    /** VARCHAR / TEXT / DECIMAL / DATETIME / TINYINT / BIGINT */
    @NotBlank(message = "请选择字段类型")
    private String type;

    /** VARCHAR 长度，或 DECIMAL 精度 */
    private Integer length;

    /** DECIMAL 小数位 */
    private Integer decimals;

    private Boolean nullable;

    @Size(max = 500, message = "字段注释不能超过 500 字")
    private String comment;

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

    public Boolean getNullable() {
        return nullable;
    }

    public void setNullable(Boolean nullable) {
        this.nullable = nullable;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
