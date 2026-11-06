package com.jqgg.glm53.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 广告牌扩展字段 / 属性定义。
 * 每条记录对应 billboard 表中的一个真实物理列（通过 ALTER TABLE 新增），
 * 因此 billboard 主实体不需要改动，扩展值由动态 SQL 读写。
 */
@Entity
@Table(name = "billboard_field")
public class BillboardField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属子模块（sys_maintenance_module.id） */
    @Column(name = "module_id", nullable = false)
    private Long moduleId;

    /** 物理列名（billboard 表中真实存在的列，全局唯一） */
    @Column(name = "field_name", nullable = false, unique = true, length = 64)
    private String fieldName;

    /** 显示名称（字段标签） */
    @Column(nullable = false, length = 100)
    private String label;

    /** 输入类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false, length = 20)
    private FieldInputType inputType;

    /** 物理列类型（如 VARCHAR(200)、DECIMAL(18,2)），用于展示与重建列 */
    @Column(name = "column_type", nullable = false, length = 50)
    private String columnType;

    /** 文本列长度（VARCHAR 用） */
    @Column(name = "field_length")
    private Integer length;

    /** 小数位数（DECIMAL 用） */
    private Integer decimals;

    /** 是否必填（应用层校验；数据库列允许 NULL，避免存量数据无法加列） */
    @Column(nullable = false)
    private boolean required = false;

    /** 是否允许数据库 NULL（物理列可空性；false 时保存广告牌必须填写或使用默认值） */
    @Column(nullable = false)
    private boolean nullable = true;

    /** 是否启用（停用后不进入表单与前台，但物理列保留） */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 表单分区：basic/location/price/params/extension */
    @Column(nullable = false, length = 30)
    private String section = "extension";

    /** 表单栏宽：half/full */
    @Column(nullable = false, length = 10)
    private String width = "half";

    /** 默认值（新增列时写入 DEFAULT；多行文本不支持默认值） */
    @Column(name = "default_value", length = 500)
    private String defaultValue;

    /** 下拉选项（换行分隔） */
    @Column(name = "options_text", columnDefinition = "TEXT")
    private String optionsText;

    /** 表单占位提示 */
    @Column(length = 200)
    private String placeholder;

    /** 排序号（升序） */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** 是否在前台详情展示 */
    @Column(nullable = false)
    private boolean visible = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getModuleId() {
        return moduleId;
    }

    public void setModuleId(Long moduleId) {
        this.moduleId = moduleId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public FieldInputType getInputType() {
        return inputType;
    }

    public void setInputType(FieldInputType inputType) {
        this.inputType = inputType;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
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

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
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

    public String getOptionsText() {
        return optionsText;
    }

    public void setOptionsText(String optionsText) {
        this.optionsText = optionsText;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
