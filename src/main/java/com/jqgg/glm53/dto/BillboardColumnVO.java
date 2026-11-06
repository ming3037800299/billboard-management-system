package com.jqgg.glm53.dto;

import java.util.List;

/**
 * billboard 表中的一个实际字段（数据库维护页展示用）。
 * 系统字段来自 billboard 表的真实列；扩展字段额外带有子模块与表单元数据。
 *
 * @param origin 来源：SYSTEM 系统字段 / CUSTOM 扩展字段
 * @param synced 扩展字段的物理列是否存在（系统字段恒为 true）
 */
public record BillboardColumnVO(
        String name,
        String type,
        String comment,
        boolean nullable,
        String defaultValue,
        String key,
        String extra,
        boolean primaryKey,
        String origin,
        Long customFieldId,
        Long moduleId,
        String moduleName,
        String moduleType,
        String label,
        String inputType,
        String inputTypeLabel,
        Integer length,
        Integer decimals,
        boolean required,
        boolean visible,
        List<String> options,
        String placeholder,
        int sortOrder,
        boolean enabled,
        String section,
        String width,
        boolean synced) {
}
