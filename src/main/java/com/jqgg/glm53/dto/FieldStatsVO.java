package com.jqgg.glm53.dto;

import java.util.List;

/**
 * 字段数据概况，用于编辑字段前评估类型 / 可空性变更的影响。
 */
public record FieldStatsVO(
        long totalRows,
        long nonNullRows,
        long nullRows,
        long distinctValues,
        List<String> sampleValues) {
}
