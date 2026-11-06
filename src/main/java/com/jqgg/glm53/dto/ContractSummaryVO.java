package com.jqgg.glm53.dto;

import java.time.LocalDateTime;

/**
 * 预定列表中展示的合同摘要。
 */
public record ContractSummaryVO(
        Long id,
        String contractNo,
        String status,
        String statusLabel,
        String source,
        String sourceLabel,
        String signingPath,
        LocalDateTime signTokenExpiresAt,
        LocalDateTime signedAt,
        boolean canSign) {
}
