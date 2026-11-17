package com.jqgg.glm53.service;

import com.jqgg.glm53.entity.BillboardImageCategory;
import com.jqgg.glm53.exception.BadRequestException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告牌图片元信息（分类 / 封面 / 原始文件名）的校验与规范化。
 * 前端提交 imageMeta（JSON 数组），后端保证：地址必须属于本次最终图片列表、
 * 分类合法、最多一个封面，并把封面排到图片列表首位。
 */
@Service
public class BillboardImageMetaService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 规范化结果：imageUrls 为封面置顶后的最终图片顺序，metaJson 为落库的结构化元信息。 */
    public record Normalized(List<String> imageUrls, String metaJson) {
    }

    private record Entry(String url, BillboardImageCategory category, boolean cover, String name) {
    }

    /**
     * @param submittedJson 前端提交的图片元信息 JSON（可为空 / 历史数据）
     * @param imageUrls     本次保存的最终图片地址（保留旧图 + 新上传，已转存）
     */
    public Normalized normalize(String submittedJson, List<String> imageUrls) {
        List<String> urls = imageUrls == null ? List.of() : imageUrls;
        Map<String, Entry> byUrl = new LinkedHashMap<>();

        if (submittedJson != null && !submittedJson.isBlank()) {
            List<Map<String, Object>> raw;
            try {
                raw = MAPPER.readValue(submittedJson, new TypeReference<>() {
                });
            } catch (Exception e) {
                throw new BadRequestException("图片分类参数格式错误");
            }
            if (raw != null) {
                for (Map<String, Object> item : raw) {
                    if (item == null) {
                        continue;
                    }
                    String url = str(item.get("url"));
                    if (url == null || !urls.contains(url)) {
                        continue;
                    }
                    BillboardImageCategory category;
                    try {
                        category = BillboardImageCategory.from(str(item.get("category")));
                    } catch (BadRequestException e) {
                        throw new BadRequestException("图片分类不合法：" + item.get("category"));
                    }
                    byUrl.put(url, new Entry(url, category, bool(item.get("cover")), str(item.get("name"))));
                }
            }
        }

        // 未被前端指定分类的图片（新增文件 / 历史数据）默认按实景照片处理
        for (String url : urls) {
            byUrl.computeIfAbsent(url, u -> new Entry(u, BillboardImageCategory.REAL_PHOTO, false, null));
        }

        String coverUrl = byUrl.values().stream()
                .filter(Entry::cover)
                .map(Entry::url)
                .findFirst()
                .orElse(urls.isEmpty() ? null : urls.get(0));

        List<String> orderedUrls = new ArrayList<>(urls.size());
        if (coverUrl != null) {
            orderedUrls.add(coverUrl);
        }
        for (String url : urls) {
            if (!url.equals(coverUrl)) {
                orderedUrls.add(url);
            }
        }

        List<Map<String, Object>> meta = new ArrayList<>(orderedUrls.size());
        for (String url : orderedUrls) {
            Entry entry = byUrl.get(url);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("url", url);
            row.put("category", entry.category().name());
            row.put("cover", url.equals(coverUrl));
            if (entry.name() != null && !entry.name().isBlank()) {
                row.put("name", entry.name());
            }
            meta.add(row);
        }

        try {
            return new Normalized(orderedUrls, MAPPER.writeValueAsString(meta));
        } catch (Exception e) {
            throw new BadRequestException("图片分类信息保存失败");
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 按地址映射重写元信息（压缩包临时图片转存到常规目录后调用），保持顺序、分类与封面不变。
     */
    public String remap(String metaJson, Map<String, String> mapping) {
        if (metaJson == null || metaJson.isBlank() || mapping.isEmpty()) {
            return metaJson;
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(metaJson, new TypeReference<>() {
            });
            List<Map<String, Object>> result = new ArrayList<>();
            if (raw != null) {
                for (Map<String, Object> item : raw) {
                    if (item == null) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>(item);
                    String url = str(item.get("url"));
                    if (url != null && mapping.containsKey(url)) {
                        row.put("url", mapping.get(url));
                    }
                    result.add(row);
                }
            }
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new BadRequestException("图片分类信息处理失败");
        }
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && ("true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value)));
    }
}
