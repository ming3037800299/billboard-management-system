package com.jqgg.glm53.dto;

import java.time.LocalDateTime;

/**
 * 后台知识库列表中的单条索引状态。
 */
public record KnowledgeDocumentVO(
        Long billboardId,
        String name,
        String code,
        String status,
        String model,
        Integer dimension,
        LocalDateTime indexedAt,
        String errorMessage) {
}
