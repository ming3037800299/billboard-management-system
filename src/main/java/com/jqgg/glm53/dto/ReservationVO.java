package com.jqgg.glm53.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预定记录视图（含广告牌快照与合同摘要）。
 */
public record ReservationVO(
        Long id,
        String reservationNo,
        Long billboardId,
        String billboardCode,
        String billboardName,
        String billboardRoad,
        String billboardSection,
        String billboardAddress,
        String billboardSize,
        String billboardMaterial,
        String billboardVisibleArea,
        BigDecimal billboardPrice,
        String billboardPriceUnit,
        String billboardPriceNote,
        String tenantType,
        String tenantTypeLabel,
        String tenantName,
        String tenantIdNumber,
        String tenantAddress,
        String tenantContact,
        String tenantPhone,
        String tenantEmail,
        String adContent,
        LocalDate startDate,
        LocalDate endDate,
        int leaseMonths,
        String leaseDuration,
        BigDecimal totalAmount,
        BigDecimal depositAmount,
        String paymentTerms,
        String specialTerms,
        String remark,
        String status,
        String statusLabel,
        LocalDateTime cancelledAt,
        String cancelReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        ContractSummaryVO contract) {
}
