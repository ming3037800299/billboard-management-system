package com.jqgg.glm53.dto;

/**
 * 数据库检索命中项。
 *
 * @param matchType 命中方式：表名 / 表注释 / 字段名 / 字段注释 / 字段类型 / 数据内容
 * @param sampleValue 数据内容命中时的示例值（字段命中时为 null）
 * @param rowRef      数据内容命中时的行标识（如 id=29）
 */
public record DbSearchHitVO(
        String database,
        String table,
        String tableComment,
        String column,
        String columnType,
        String columnComment,
        String matchType,
        String sampleValue,
        String rowRef) {
}
