package com.jqgg.glm53.dto;

/**
 * 统一 API 响应包装。
 * 成功：{ "success": true,  "message": null,  "data": ... }
 * 失败：{ "success": false, "message": "原因", "data": null }
 */
public record ApiResult<T>(boolean success, String message, T data) {

    /** 成功并携带数据。 */
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(true, null, data);
    }

    /** 成功无数据。 */
    public static ApiResult<Void> ok() {
        return new ApiResult<>(true, null, null);
    }

    /** 失败并携带提示信息。 */
    public static <T> ApiResult<T> error(String message) {
        return new ApiResult<>(false, message, null);
    }
}
