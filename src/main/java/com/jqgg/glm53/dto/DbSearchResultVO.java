package com.jqgg.glm53.dto;

import java.util.List;

/**
 * 数据库检索结果。
 *
 * @param columnHits          表 / 字段 / 注释命中
 * @param dataHits            表内数据内容命中（仅在勾选“同时检索数据内容”时返回）
 * @param dataSearchTruncated 数据检索是否因结果过多而截断
 * @param scannedTables       本次扫描的表数量
 */
public record DbSearchResultVO(
        String keyword,
        boolean includeData,
        List<DbSearchHitVO> columnHits,
        List<DbSearchHitVO> dataHits,
        boolean dataSearchTruncated,
        int scannedTables) {
}
