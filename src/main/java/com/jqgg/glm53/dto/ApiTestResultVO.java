package com.jqgg.glm53.dto;

/**
 * 接口连通性测试结果。
 * 测试失败属于正常业务结果（HTTP 仍为 200），前端根据 success 展示成功 / 失败提示。
 */
public record ApiTestResultVO(
        boolean success,
        String message,
        long latencyMs,
        Integer httpStatus
) {
}
