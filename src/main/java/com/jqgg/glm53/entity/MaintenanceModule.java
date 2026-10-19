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
 * 数据库维护子模块。
 * 后台“数据库维护”页左侧列出的每一项都是一个子模块，可自由新增：
 *   - 检索 / 表结构：只读工具
 *   - 广告牌字段 / 属性：该子模块下维护一组扩展字段，字段新增时同步 ALTER billboard 表
 */
@Entity
@Table(name = "sys_maintenance_module")
public class MaintenanceModule {

    /** 主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 模块标识（唯一，英文/数字/下划线） */
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    /** 模块类型 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MaintenanceModuleType type;

    /** 说明文字 */
    @Column(length = 500)
    private String description;

    /** 排序号（升序） */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** 是否启用（停用后不出现在广告牌表单与前台详情） */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 是否系统内置（内置模块不可删除） */
    @Column(nullable = false)
    private boolean builtin = false;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public MaintenanceModuleType getType() {
        return type;
    }

    public void setType(MaintenanceModuleType type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
