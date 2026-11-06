package com.jqgg.glm53.dto;

import java.time.LocalDateTime;

/**
 * 合同文件版本视图。
 */
public record ContractFileVO(
        Long id,
        String fileType,
        String originalName,
        long fileSize,
        String downloadUrl,
        LocalDateTime createdAt) {
}
