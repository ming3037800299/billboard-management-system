package com.jqgg.glm53.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新增 / 编辑广告牌扩展字段的请求体。
 * 字段名、输入类型、长度、小数位在创建后不可修改（避免数据丢失），编辑时仅更新展示属性。
 */
public class BillboardFieldForm {

    @NotBlank(message = "请填写字段显示名称")
    @Size(max = 100, message = "字段显示名称不能超过 100 字")
    private String label;

    /** 物理列名（新增时必填；编辑时忽略） */
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,62}$",
            message = "字段名需为小写字母开头，可包含数字和下划线（2-63 位）")
    private String fieldName;

    /** TEXT / TEXTAREA / INTEGER / DECIMAL / DATE / DATETIME / SELECT / BOOLEAN */
    private String inputType;

    private Integer length;

    private Integer decimals;

    private Boolean required;

    /** 是否允许数据库 NULL；未传时默认允许 */
    private Boolean nullable;

    /** 是否启用（停用后不进入表单与前台） */
    private Boolean enabled;

    /** 表单分区：basic / location / price / params / extension */
    @Size(max = 30, message = "表单分区不合法")
    private String section;

    /** 表单栏宽：half / full */
    @Size(max = 10, message = "表单栏宽不合法")
    private String width;

    @Size(max = 500, message = "默认值不能超过 500 字")
    private String defaultValue;

    private List<String> options;

    @Size(max = 200, message = "提示文字不能超过 200 字")
    private String placeholder;

    private Integer sortOrder;

    private Boolean visible;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    public Integer getLength() {
        return length;
    }

    public void setLength(Integer length) {
        this.length = length;
    }

    public Integer getDecimals() {
        return decimals;
    }

    public void setDecimals(Integer decimals) {
        this.decimals = decimals;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public Boolean getNullable() {
        return nullable;
    }

    public void setNullable(Boolean nullable) {
        this.nullable = nullable;
    }

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

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options = options;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }
}
