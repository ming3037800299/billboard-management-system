package com.jqgg.glm53.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 合同模板视图。
 */
public record ContractTemplateVO(
        Long id,
        String name,
        String originalName,
        boolean defaultTemplate,
        boolean enabled,
        List<String> placeholders,
        String downloadUrl,
        LocalDateTime createdAt) {
}
