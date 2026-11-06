package com.jqgg.glm53.dto;

import jakarta.validation.constraints.Size;

/**
 * 字段管理 / 表单设计使用的轻量布局更新请求。
 * 只修改启用状态、表单分区、栏宽、排序等展示配置，不触发表结构变更。
 */
public class FieldLayoutForm {

    private Boolean enabled;

    @Size(max = 30, message = "表单分区不合法")
    private String section;

    @Size(max = 10, message = "表单栏宽不合法")
    private String width;

    private Integer sortOrder;

    private Boolean required;

    private Boolean visible;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public String getWidth() {
        return width;
    }

    public void setWidth(String width) {
        this.width = width;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }
}
