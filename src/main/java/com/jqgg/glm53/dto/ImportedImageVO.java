package com.jqgg.glm53.dto;

/**
 * 压缩包 / 批量选择导入后的单张图片结果。
 * 除落库所需的 url / category / name 外，额外返回尺寸、置信度与识别依据，便于后台预览时人工复核。
 */
public record ImportedImageVO(
        String url,
        String name,
        String category,
        String categoryLabel,
        int width,
        int height,
        boolean cover,
        double confidence,
        String reason,
        boolean cameraInfo) {
}
