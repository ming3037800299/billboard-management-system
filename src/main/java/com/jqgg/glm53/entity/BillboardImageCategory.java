package com.jqgg.glm53.entity;

/**
 * 广告牌图片分类。
 * 上传压缩包或直接选择图片时，系统按文件名关键词与图片基础特征自动归类，
 * 归类结果随广告牌一起保存，后台分组展示并允许人工调整。
 */
public enum BillboardImageCategory {

    /** 宣传图：海报、主视觉、广告画面等设计定稿 */
    PROMO,

    /** 实景照片：现场实拍、航拍、点位照片 */
    REAL_PHOTO,

    /** 设计图纸：平面图、效果图、施工图等 */
    DESIGN_DRAWING;

    /** 返回该分类对应的中文标签。 */
    public String label() {
        return switch (this) {
            case PROMO -> "宣传图";
            case REAL_PHOTO -> "实景照片";
            case DESIGN_DRAWING -> "设计图纸";
        };
    }

    /** 宽松解析分类名（大小写不敏感）；空值按实景照片处理。 */
    public static BillboardImageCategory from(String raw) {
        if (raw == null || raw.isBlank()) {
            return REAL_PHOTO;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.jqgg.glm53.exception.BadRequestException("未知的图片分类：" + raw);
        }
    }
}
