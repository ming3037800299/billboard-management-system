package com.jqgg.glm53.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库整体状态（后台知识库管理页使用）。
 */
public record KnowledgeStatusVO(
        boolean enabled,
        boolean embeddingAvailable,
        String embeddingModel,
        boolean llmConfigured,
        String llmModel,
        int totalBillboards,
        int readyCount,
        int pendingCount,
        int errorCount,
        LocalDateTime lastIndexedAt,
        List<KnowledgeDocumentVO> documents) {
}
