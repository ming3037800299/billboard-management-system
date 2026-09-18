package com.jqgg.glm53.exception;

/**
 * 资源不存在（404）：广告牌 ID 查询不到等。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
