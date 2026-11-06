package com.jqgg.glm53.entity;

/**
 * 数据库维护子模块类型：
 *   SEARCH               数据库检索（按表名 / 字段名 / 注释 / 数据内容定位）
 *   SCHEMA               数据表结构浏览
 *   BILLBOARD_FIELD      广告牌扩展字段（新增物理列）
 *   BILLBOARD_ATTRIBUTE  广告牌扩展属性（新增物理列，偏业务属性）
 */
public enum MaintenanceModuleType {

    SEARCH("数据库检索"),
    SCHEMA("数据表结构"),
    BILLBOARD_FIELD("广告牌字段"),
    BILLBOARD_ATTRIBUTE("广告牌属性"),
    FIELD_MANAGER("字段管理"),
    FORM_DESIGNER("表单设计");

    private final String label;

    MaintenanceModuleType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 该类型是否用于维护广告牌扩展字段 / 属性。 */
    public boolean isFieldModule() {
        return this == BILLBOARD_FIELD || this == BILLBOARD_ATTRIBUTE;
    }

    /** 容错解析：非法值返回 null，由调用方给出业务提示。 */
    public static MaintenanceModuleType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
