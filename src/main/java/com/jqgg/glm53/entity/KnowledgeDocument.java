package com.jqgg.glm53.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 广告牌知识库文档：一条广告牌对应一条知识文本及其向量。
 * 向量以 JSON 数组字符串保存在 embedding 字段中，检索时在应用内做余弦相似度计算。
 */
@Entity
@Table(name = "billboard_knowledge")
public class KnowledgeDocument {

    /** 与 billboard.id 一一对应 */
    @Id
    @Column(name = "billboard_id")
    private Long billboardId;

    /** 用于向量化与问答的知识文本（结构化字段拼接） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 内容 + 模型名称的 SHA-256，内容未变化时跳过重复向量化 */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 向量 JSON 数组字符串 */
    @Column(columnDefinition = "TEXT")
    private String embedding;

    /** 向量维度 */
    private Integer dimension;

    /** 向量模型名称 */
    @Column(length = 100)
    private String model;

    /** PENDING / READY / ERROR */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    /** 最近一次索引错误信息 */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** 最近成功索引时间 */
    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    // ------------------- getter / setter -------------------


    public Long getBillboardId() {
        return billboardId;
    }

    public void setBillboardId(Long billboardId) {
        this.billboardId = billboardId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }

    public Integer getDimension() {
        return dimension;
    }

    public void setDimension(Integer dimension) {
        this.dimension = dimension;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(LocalDateTime indexedAt) {
        this.indexedAt = indexedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
