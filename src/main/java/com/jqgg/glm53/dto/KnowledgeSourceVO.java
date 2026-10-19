package com.jqgg.glm53.dto;

import java.math.BigDecimal;

/**
 * 知识库检索命中的广告牌摘要（含相似度分数）。
 */
public record KnowledgeSourceVO(
        Long id,
        String name,
        String code,
        String road,
        String section,
        BigDecimal price,
        String priceUnit,
        String status,
        String statusLabel,
        double score,
        String matchType) {
}
