package com.jqgg.glm53.dto;

import com.jqgg.glm53.entity.Billboard;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 广告牌视图对象：面向前端（前台展示 + 后台管理）的 JSON 结构。
 * 与实体的主要差异：images 由 JSON 字符串解析为图片路径数组，
 * 状态同时给出枚举值（用于提交）与中文标签（用于展示），
 * extraValues 为数据库维护模块动态新增的字段/属性值（字段名 → 值）。
 */
public record BillboardVO(
        Long id,
        String name,
        String code,
        String road,
        String section,
        String address,
        BigDecimal longitude,
        BigDecimal latitude,
        List<String> images,
        List<BillboardImageMetaVO> imageMeta,
        BigDecimal price,
        String priceUnit,
        String priceNote,
        String size,
        String material,
        String visibleArea,
        String audience,
        String status,
        String statusLabel,
        String description,
        boolean deleted,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Map<String, Object> extraValues) {

    /** 静态共享的 JSON 解析器（解析 images 字段）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 由实体转换为视图对象（不含扩展字段值）。 */
    public static BillboardVO from(Billboard b) {
        return from(b, Map.of());
    }

    /** 由实体转换为视图对象，并附带动态扩展字段值。 */
    public static BillboardVO from(Billboard b, Map<String, Object> extraValues) {
        List<String> images = parseImages(b.getImages());
        return new BillboardVO(
                b.getId(),
                b.getName(),
                b.getCode(),
                b.getRoad(),
                b.getSection(),
                b.getAddress(),
                b.getLongitude(),
                b.getLatitude(),
                images,
                BillboardImageMetaVO.parse(b.getImageMeta(), images),
                b.getPrice(),
                b.getPriceUnit(),
                b.getPriceNote(),
                b.getSize(),
                b.getMaterial(),
                b.getVisibleArea(),
                b.getAudience(),
                b.getStatus().name(),
                b.getStatus().label(),
                b.getDescription(),
                b.isDeleted(),
                b.getCreatedAt(),
                b.getUpdatedAt(),
                extraValues == null ? Map.of() : extraValues);
    }

    /** 把 images 字段的 JSON 数组字符串解析为列表；空值或格式异常时返回空列表。 */
    private static List<String> parseImages(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = MAPPER.readValue(imagesJson, new TypeReference<>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 把图片路径列表序列化为 JSON 数组字符串（存库格式）。 */
    public static String writeImages(List<String> imageUrls) {
        try {
            return MAPPER.writeValueAsString(imageUrls == null ? List.of() : imageUrls);
        } catch (Exception e) {
            return "[]";
        }
    }
}
