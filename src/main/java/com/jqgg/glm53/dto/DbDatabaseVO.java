package com.jqgg.glm53.dto;

/**
 * 数据库列表项。
 *
 * @param current 是否为当前应用连接的默认数据库
 */
public record DbDatabaseVO(
        String name,
        long tableCount,
        long columnCount,
        boolean current) {
}
