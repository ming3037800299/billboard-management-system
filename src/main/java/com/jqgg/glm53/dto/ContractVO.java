package com.jqgg.glm53.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 合同列表 / 详情视图。
 */
public record ContractVO(
        Long id,
        String contractNo,
        Long reservationId,
        Long billboardId,
        String billboardCode,
        String billboardName,
        String billboardRoad,
        String billboardSection,
        String tenantType,
        String tenantTypeLabel,
        String tenantName,
        String tenantPhone,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalAmount,
        String templateName,
        String source,
        String sourceLabel,
        String status,
        String statusLabel,
        String currentFileName,
        LocalDateTime signedAt,
        String signerName,
        String signingPath,
        LocalDateTime signTokenExpiresAt,
        boolean signTokenRevoked,
        String voidReason,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt) {
}
