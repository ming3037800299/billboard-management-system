package com.jqgg.glm53.exception;

/**
 * 业务校验失败（400）：参数缺失、编号重复、格式不合法等。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
