package com.jqgg.glm53.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 新增 / 编辑广告牌的表单对象（multipart/form-data 提交）。
 * 说明：multipart 表单所有字段均以字符串到达，故数值/坐标字段先按 String 接收，
 * 由控制器统一转换为对应类型并校验；空字符串一律按"未填写（null）"处理。
 */
public class SaveBillboardForm {

    /** 广告牌名称（必填） */
    @NotBlank(message = "请填写广告牌名称")
    @Size(max = 100, message = "名称不能超过 100 字")
    private String name;

    /** 广告牌编号（必填，全局唯一） */
    @NotBlank(message = "请填写广告牌编号")
    @Size(max = 50, message = "编号不能超过 50 字")
    private String code;

    private String road;

    @Size(max = 200, message = "路段位置不能超过 200 字")
    private String section;

    @Size(max = 500, message = "详细地址不能超过 500 字")
    private String address;

    /** 经度（字符串形式，如 115.464600） */
    private String longitude;

    /** 纬度（字符串形式，如 38.748900） */
    private String latitude;

    /** 租赁单价（字符串形式） */
    private String price;

    /** 计费周期：元/月、元/年 */
    private String priceUnit;

    @Size(max = 500, message = "报价说明不能超过 500 字")
    private String priceNote;

    @Size(max = 100, message = "尺寸不能超过 100 字")
    private String size;

    @Size(max = 100, message = "材质不能超过 100 字")
    private String material;

    @Size(max = 100, message = "可视面积不能超过 100 字")
    private String visibleArea;

    @Size(max = 500, message = "受众范围不能超过 500 字")
    private String audience;

    /** 租赁状态：AVAILABLE / RENTED / PAUSED */
    @NotBlank(message = "请选择租赁状态")
    private String status;

    private String description;

    /** 本次新上传的图片文件（0 ~ 多张） */
    private List<MultipartFile> images;

    /** 编辑时保留的已有图片 URL 数组（JSON 字符串），新增时忽略 */
    private String keepImages;

    /** 图片元信息（JSON 数组：url / category / cover / name），与最终图片列表对应 */
    private String imageMeta;

    /** 动态扩展字段值（JSON 对象：字段名 → 值），由数据库维护模块配置 */
    private String extraValues;

    // ------------------- getter / setter -------------------

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

    public String getLongitude() {
        return longitude;
    }

    public void setLongitude(String longitude) {
        this.longitude = longitude;
    }

    public String getLatitude() {
        return latitude;
    }

    public void setLatitude(String latitude) {
        this.latitude = latitude;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<MultipartFile> getImages() {
        return images;
    }

    public void setImages(List<MultipartFile> images) {
        this.images = images;
    }

    public String getKeepImages() {
        return keepImages;
    }

    public void setKeepImages(String keepImages) {
        this.keepImages = keepImages;
    }

    public String getImageMeta() {
        return imageMeta;
    }

    public void setImageMeta(String imageMeta) {
        this.imageMeta = imageMeta;
    }

    public String getExtraValues() {
        return extraValues;
    }

    public void setExtraValues(String extraValues) {
        this.extraValues = extraValues;
    }
}
