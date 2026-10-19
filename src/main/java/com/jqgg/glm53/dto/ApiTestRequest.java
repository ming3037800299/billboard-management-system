package com.jqgg.glm53.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 连通性测试请求：既支持已保存配置（传 id，Key 留空复用数据库中的 Key），
 * 也支持保存前用当前表单值直接测试。
 */
public record ApiTestRequest(
        Long id,
        @NotBlank(message = "请选择接口类型") String type,
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        Integer timeoutSeconds
) {
}
