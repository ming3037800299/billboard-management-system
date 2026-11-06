package com.jqgg.glm53.dto;

import com.jqgg.glm53.entity.BillboardField;
import com.jqgg.glm53.entity.FieldInputType;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 广告牌扩展字段视图对象。
 */
public record BillboardFieldVO(
        Long id,
        Long moduleId,
        String moduleName,
        String moduleType,
        String fieldName,
        String label,
        String inputType,
        String inputTypeLabel,
        String columnType,
        Integer length,
        Integer decimals,
        boolean required,
        boolean nullable,
        String defaultValue,
        List<String> options,
        String placeholder,
        int sortOrder,
        boolean visible,
        boolean enabled,
        String section,
        String width,
        boolean synced,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 由实体生成视图对象。 */
    public static BillboardFieldVO from(BillboardField f, String moduleName, String moduleType, boolean synced) {
        return new BillboardFieldVO(
                f.getId(),
                f.getModuleId(),
                moduleName,
                moduleType,
                f.getFieldName(),
                f.getLabel(),
                f.getInputType() == null ? null : f.getInputType().name(),
                f.getInputType() == null ? null : f.getInputType().getLabel(),
                f.getColumnType(),
                f.getLength(),
                f.getDecimals(),
                f.isRequired(),
                f.isNullable(),
                f.getDefaultValue(),
                parseOptions(f.getOptionsText()),
                f.getPlaceholder(),
                f.getSortOrder(),
                f.isVisible(),
                f.isEnabled(),
                f.getSection(),
                f.getWidth(),
                synced,
                f.getCreatedAt(),
                f.getUpdatedAt());
    }

    /** 换行分隔的选项文本 → 选项列表。 */
    public static List<String> parseOptions(String optionsText) {
        if (optionsText == null || optionsText.isBlank()) {
            return List.of();
        }
        return Arrays.stream(optionsText.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /** 选项列表 → 换行分隔的存储文本。 */
    public static String writeOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        return options.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .reduce((a, b) -> a + "\n" + b)
                .orElse(null);
    }
}
