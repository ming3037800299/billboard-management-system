package com.jqgg.glm53.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 乙方签署页所需的合同公开信息（不包含管理员数据）。
 */
public record SignInfoVO(
        String contractNo,
        String status,
        String statusLabel,
        boolean canSign,
        String blockedReason,
        String billboardName,
        String billboardCode,
        String billboardRoad,
        String billboardSection,
        String tenantName,
        String tenantTypeLabel,
        String tenantContact,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalAmount,
        String partyAName,
        String signerName,
        LocalDateTime signedAt,
        LocalDateTime tokenExpiresAt,
        String fileName,
        String fileUrl,
        String downloadUrl,
        String siteName) {
}
