package com.jqgg.glm53.dto;

import java.time.LocalDateTime;

/**
 * 合同操作日志视图。
 */
public record ContractEventVO(
        Long id,
        String action,
        String actorType,
        String detail,
        LocalDateTime createdAt) {
}
