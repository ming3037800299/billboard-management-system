package com.jqgg.glm53.dto;

import java.util.List;

/**
 * 数据表及其字段结构。
 *
 * @param rows 表内数据量（MySQL 为 information_schema 估算值，H2 为精确值）
 */
public record DbTableVO(
        String database,
        String name,
        String comment,
        long rows,
        List<DbColumnVO> columns) {
}
