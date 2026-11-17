package com.jqgg.glm53.dto;

import com.jqgg.glm53.entity.BillboardImageCategory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 广告牌单张图片的元信息（视图对象）：图片地址、分类、是否封面、原始文件名。
 * 数据库 billboard.image_meta 以 JSON 数组保存，解析时兼容历史数据（无 meta 时默认实景照片、首图为封面）。
 */
public record BillboardImageMetaVO(String url, String category, String categoryLabel, boolean cover, String name) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 由数据库 JSON 解析图片元信息；缺项按图片列表补齐，保证与 images 一一对应。 */
    public static List<BillboardImageMetaVO> parse(String metaJson, List<String> images) {
        List<String> urls = images == null ? List.of() : images;
        Map<String, BillboardImageMetaVO> saved = new LinkedHashMap<>();
        if (metaJson != null && !metaJson.isBlank()) {
            try {
                List<Map<String, Object>> raw = MAPPER.readValue(metaJson, new TypeReference<>() {
                });
                if (raw != null) {
                    for (Map<String, Object> item : raw) {
                        if (item == null) {
                            continue;
                        }
                        String url = str(item.get("url"));
                        if (url == null || url.isBlank() || !urls.contains(url)) {
                            continue;
                        }
                        BillboardImageCategory category = categoryOf(str(item.get("category")));
                        boolean cover = bool(item.get("cover"));
                        String name = str(item.get("name"));
                        saved.put(url, new BillboardImageMetaVO(
                                url, category.name(), category.label(), cover, name));
                    }
                }
            } catch (Exception e) {
                saved.clear();
            }
        }

        List<BillboardImageMetaVO> result = new ArrayList<>(urls.size());
        Set<String> used = new LinkedHashSet<>();
        boolean coverAssigned = false;
        // 先输出数据库中的顺序（封面天然排在最前），再补齐历史数据中缺失的图片
        for (BillboardImageMetaVO item : saved.values()) {
            if (used.add(item.url())) {
                boolean isCover = item.cover() && !coverAssigned;
                coverAssigned |= isCover;
                result.add(isCover == item.cover() ? item
                        : new BillboardImageMetaVO(item.url(), item.category(), item.categoryLabel(), isCover, item.name()));
            }
        }
        for (String url : urls) {
            if (used.add(url)) {
                BillboardImageCategory category = BillboardImageCategory.REAL_PHOTO;
                result.add(new BillboardImageMetaVO(url, category.name(), category.label(), false, null));
            }
        }
        if (!result.isEmpty() && !result.stream().anyMatch(BillboardImageMetaVO::cover)) {
            BillboardImageMetaVO first = result.get(0);
            result.set(0, new BillboardImageMetaVO(first.url(), first.category(), first.categoryLabel(), true, first.name()));
        }
        return result;
    }

    private static BillboardImageCategory categoryOf(String raw) {
        try {
            return BillboardImageCategory.from(raw);
        } catch (RuntimeException e) {
            return BillboardImageCategory.REAL_PHOTO;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && ("true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value)));
    }
}
