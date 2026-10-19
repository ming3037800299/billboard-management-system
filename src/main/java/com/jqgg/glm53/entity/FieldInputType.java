package com.jqgg.glm53.entity;

import java.util.Locale;

/**
 * 广告牌扩展字段的输入类型。
 * 决定后台表单控件、前台展示格式，以及新增列时使用的物理列类型。
 */
public enum FieldInputType {

    TEXT("单行文本"),
    TEXTAREA("多行文本"),
    INTEGER("整数"),
    DECIMAL("小数"),
    DATE("日期"),
    DATETIME("日期时间"),
    SELECT("下拉选项"),
    BOOLEAN("是/否");

    private final String label;

    FieldInputType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 容错解析：非法值返回 null。 */
    public static FieldInputType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
