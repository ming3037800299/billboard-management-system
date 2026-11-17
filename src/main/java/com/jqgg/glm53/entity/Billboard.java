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
import org.hibernate.annotations.DynamicInsert;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 广告牌实体，映射核心数据表 billboard。
 * 字段与前台展示、后台编辑的数据项一一对应；时间戳由应用层维护，
 * 数据库端另设 DEFAULT CURRENT_TIMESTAMP 兜底（见 sql/init.sql）。
 */
@Entity
@DynamicInsert
@Table(name = "billboard")
public class Billboard {

    /** 主键ID（唯一自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 广告牌名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 广告牌编号（唯一标识） */
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    /** 所在公路（如 京沪高速G2） */
    @Column(length = 100)
    private String road;

    /** 路段位置（如 保定南段 K1123+500） */
    @Column(length = 200)
    private String section;

    /** 详细地址 */
    @Column(length = 500)
    private String address;

    /** 经度（GCJ-02 坐标系） */
    @Column(precision = 10, scale = 6)
    private BigDecimal longitude;

    /** 纬度（GCJ-02 坐标系） */
    @Column(precision = 10, scale = 6)
    private BigDecimal latitude;

    /** 图片路径集合（JSON 数组字符串，支持多图） */
    @Column(columnDefinition = "TEXT")
    private String images;

    /**
     * 图片元信息（JSON 数组：图片地址 / 分类 / 是否封面 / 原始文件名）。
     * 分类由压缩包导入或图片选择时自动识别，后台可人工调整后随广告牌保存。
     */
    @Column(name = "image_meta", columnDefinition = "TEXT")
    private String imageMeta;

    /** 租赁单价（对应 priceUnit 计费周期） */
    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    /** 计费周期：元/月、元/年 */
    @Column(length = 20)
    private String priceUnit;

    /** 报价说明 */
    @Column(length = 500)
    private String priceNote;

    /** 尺寸（如 18m×6m） */
    @Column(length = 100)
    private String size;

    /** 材质（如 钢结构高炮） */
    @Column(length = 100)
    private String material;

    /** 可视面积 */
    @Column(length = 100)
    private String visibleArea;

    /** 受众范围 */
    @Column(length = 500)
    private String audience;

    /** 租赁状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillboardStatus status = BillboardStatus.AVAILABLE;

    /** 详细简介（优势、适配行业等备注） */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 软删除标记：false 正常 / true 已删除 */
    @Column(nullable = false)
    private boolean deleted = false;

    /** 创建时间（自动记录） */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间（自动记录） */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** 新增前自动记录创建时间与更新时间。 */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /** 更新前自动刷新更新时间。 */
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getRoad() {
        return road;
    }

    public void setRoad(String road) {
        this.road = road;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public String getImageMeta() {
        return imageMeta;
    }

    public void setImageMeta(String imageMeta) {
        this.imageMeta = imageMeta;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getPriceUnit() {
        return priceUnit;
    }

    public void setPriceUnit(String priceUnit) {
        this.priceUnit = priceUnit;
    }

    public String getPriceNote() {
        return priceNote;
    }

    public void setPriceNote(String priceNote) {
        this.priceNote = priceNote;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public String getVisibleArea() {
        return visibleArea;
    }

    public void setVisibleArea(String visibleArea) {
        this.visibleArea = visibleArea;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public BillboardStatus getStatus() {
        return status;
    }

    public void setStatus(BillboardStatus status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
