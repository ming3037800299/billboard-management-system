package com.jqgg.glm53.dto;

import java.time.LocalDateTime;

/**
 * API 配置视图对象：API Key 只返回脱敏值，原文不出后端。
 */
public record ApiConfigVO(
        Long id,
        String name,
        String type,
        String typeLabel,
        String provider,
        String providerLabel,
        String baseUrl,
        String model,
        String apiKeyMasked,
        boolean hasApiKey,
        Integer maxTokens,
        Double temperature,
        Integer timeoutSeconds,
        boolean enabled,
        boolean isDefault,
        boolean effective,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
