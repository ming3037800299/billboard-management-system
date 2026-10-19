package com.jqgg.glm53.dto;

import java.time.LocalDateTime;

/**
 * 数据库维护子模块视图对象。
 */
public record MaintenanceModuleVO(
        Long id,
        String name,
        String code,
        String type,
        String typeLabel,
        String description,
        int sortOrder,
        boolean enabled,
        boolean builtin,
        long fieldCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
