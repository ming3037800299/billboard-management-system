package com.jqgg.glm53.dto;

/**
 * 数据表字段结构信息。
 */
public record DbColumnVO(
        String name,
        String type,
        String comment,
        boolean nullable,
        String defaultValue,
        String key,
        String extra,
        boolean primaryKey) {
}
