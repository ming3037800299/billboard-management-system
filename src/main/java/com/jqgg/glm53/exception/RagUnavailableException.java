package com.jqgg.glm53.exception;

/**
 * 知识库依赖不可用（503）：本地向量模型未启动、模型缺失或调用失败等。
 */
public class RagUnavailableException extends RuntimeException {

    public RagUnavailableException(String message) {
        super(message);
    }

    public RagUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
