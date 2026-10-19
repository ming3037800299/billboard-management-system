package com.jqgg.glm53.dto;

import java.util.List;

/**
 * 知识库问答结果：自然语言答案 + 命中的广告牌来源。
 * llmConfigured=false 表示未配置云端问答模型，answer 为检索结果摘要。
 */
public record KnowledgeAnswerVO(
        String answer,
        List<KnowledgeSourceVO> sources,
        boolean llmConfigured,
        String llmModel,
        String embeddingModel,
        long tookMs) {
}
