package com.jqgg.glm53.dto;

/**
 * 压缩包导入时被跳过的文件及原因（非图片、超限、非法路径等）。
 */
public record SkippedArchiveFileVO(String name, String reason) {
}
