package com.jqgg.glm53.dto;

import java.util.List;

/**
 * 压缩包批量导入结果：已识别的图片按归档顺序返回（前端按 category 分组展示），
 * skipped 记录非图片 / 超限 / 非法路径等被过滤的文件，供后台提示。
 */
public record ZipImportResultVO(
        String sessionId,
        String archiveName,
        int totalEntries,
        int accepted,
        int skippedCount,
        List<ImportedImageVO> images,
        List<SkippedArchiveFileVO> skipped) {
}
