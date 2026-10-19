package com.jqgg.glm53.dto;

import java.util.List;

/**
 * 前台公开的广告牌扩展字段元数据（仅包含启用且允许前台展示的字段）。
 */
public record PublicBillboardFieldVO(
        String fieldName,
        String label,
        String inputType,
        boolean required,
        List<String> options,
        String placeholder,
        int sortOrder,
        String moduleName,
        String moduleType) {
}
