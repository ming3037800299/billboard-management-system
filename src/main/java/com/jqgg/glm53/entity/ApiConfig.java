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
 * 外部接口配置（后台 API 管理）。
 *
 * <p>数据库中的配置优先于 application.yml；只有某类型没有启用配置时才回退 yml。
 * API Key 在数据库中明文保存（属于后台运维数据），列表接口只返回脱敏值。</p>
 */
@Entity
@Table(name = "api_config")
public class ApiConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 展示名称，同类内用于区分不同线路 */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** 接口类型 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApiType type;

    /** 协议 / 服务商：OLLAMA、OPENAI、TENCENT、HTTP */
    @Column(nullable = false, length = 30)
    private String provider;

    /** 接口根地址（不含末尾斜杠） */
    @Column(name = "base_url", length = 500)
    private String baseUrl;

    /** API Key / 地图 AK（列表接口脱敏） */
    @Column(name = "api_key", length = 1000)
    private String apiKey;

    /** 模型名称（向量模型 / 对话模型） */
    @Column(length = 200)
    private String model;

    /** 单次回答最大 token 数（LLM） */
    @Column(name = "max_tokens")
    private Integer maxTokens;

    /** 采样温度（LLM，0 ~ 2） */
    private Double temperature;

    /** 读取超时（秒） */
    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds = 60;

    /** 是否启用 */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 是否为同类默认配置（同一类型最多一条） */
    @Column(name = "is_default", nullable = false)
    private boolean preferred = false;

    /** 备注 */
    @Column(length = 500)
    private String remark;

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

    // ------------------- getter / setter -------------------

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ApiType getType() {
        return type;
    }

    public void setType(ApiType type) {
        this.type = type;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPreferred() {
        return preferred;
    }

    public void setPreferred(boolean preferred) {
        this.preferred = preferred;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
