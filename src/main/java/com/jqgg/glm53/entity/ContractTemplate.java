package com.jqgg.glm53.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Word 合同模板。模板文件存放在私有目录，不能通过 /uploads/** 直接访问。
 */
@Entity
@Table(name = "contract_template")
public class ContractTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模板名称 */
    @Column(nullable = false, length = 200)
    private String name;

    /** 模板文件（私有目录相对路径） */
    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    /** 上传时的原始文件名 */
    @Column(name = "original_name", length = 255)
    private String originalName;

    /** 识别到的占位符（换行分隔，图片占位符带 @ 前缀） */
    @Column(columnDefinition = "TEXT")
    private String placeholders;

    /** 是否默认模板 */
    @Column(name = "is_default", nullable = false)
    private boolean defaultTemplate = false;

    /** 是否启用 */
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getPlaceholders() {
        return placeholders;
    }

    public void setPlaceholders(String placeholders) {
        this.placeholders = placeholders;
    }

    public boolean isDefaultTemplate() {
        return defaultTemplate;
    }

    public void setDefaultTemplate(boolean defaultTemplate) {
        this.defaultTemplate = defaultTemplate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
