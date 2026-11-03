package com.jqgg.glm53.dto;

import java.util.List;

/**
 * 数据库维护页可访问的数据库集合。
 */
public record DbDatabaseListVO(
        String defaultDatabase,
        List<DbDatabaseVO> databases) {
}
