package com.jqgg.glm53.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 后台登录请求体：{ "password": "..." }
 */
public record LoginRequest(@NotBlank(message = "请输入管理密码") String password) {
}
