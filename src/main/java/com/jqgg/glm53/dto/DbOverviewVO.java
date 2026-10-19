package com.jqgg.glm53.dto;

/**
 * 数据库维护概览：连接信息、对象数量与 DDL 权限状态。
 */
public record DbOverviewVO(
        String product,
        String version,
        String database,
        String schema,
        int tableCount,
        int columnCount,
        boolean ddlAllowed,
        String ddlHint) {
}
