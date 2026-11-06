package com.jqgg.glm53.exception;

import com.jqgg.glm53.dto.ApiResult;
import jakarta.validation.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把各类异常转换为统一的 ApiResult JSON 响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务校验失败 → 400 */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResult<Void>> handleBadRequest(BadRequestException e) {
        return ResponseEntity.badRequest().body(ApiResult.error(e.getMessage()));
    }

    /** 知识库依赖不可用（本地向量模型未启动 / 调用失败）→ 503 */
    @ExceptionHandler(RagUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> handleRagUnavailable(RagUnavailableException e) {
        log.warn("知识库服务不可用：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResult.error(e.getMessage()));
    }

    /** 资源不存在 → 404（含前端误访问的静态资源路径） */
    @ExceptionHandler({NotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResult<Void>> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResult.error("请求的资源不存在"));
    }

    /** 请求体缺失 / JSON 格式错误 → 400 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResult<Void>> handleUnreadable(Exception e) {
        return ResponseEntity.badRequest().body(ApiResult.error("请求参数不完整或格式错误"));
    }

    /** @Valid 校验失败 → 400，返回首条校验错误 */
    @ExceptionHandler({MethodArgumentNotValidException.class, ValidationException.class})
    public ResponseEntity<ApiResult<Void>> handleValidation(Exception e) {
        String message = "参数校验失败";
        if (e instanceof MethodArgumentNotValidException manv && manv.getBindingResult().getFieldError() != null) {
            message = manv.getBindingResult().getFieldError().getDefaultMessage();
        } else if (e.getCause() instanceof ValidationException ve && ve.getMessage() != null) {
            message = ve.getMessage();
        }
        return ResponseEntity.badRequest().body(ApiResult.error(message));
    }

    /** 上传文件过大 / multipart 异常 → 400 */
    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public ResponseEntity<ApiResult<Void>> handleUpload(Exception e) {
        return ResponseEntity.badRequest().body(ApiResult.error("文件上传失败：单个文件最大 20MB，单次最多 80MB"));
    }

    /** 请求 Content-Type 不受支持（如 multipart 接口收到表单编码）→ 415 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResult.error("请求格式不正确，请使用页面提供的上传表单重试"));
    }

    /**
     * SSE 客户端断开 / 异步超时：连接已经不可用，不能再按 JSON 写响应体，
     * 否则会额外触发 HttpMessageNotWritableException。这里只记录调试日志并结束。
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class})
    public ResponseEntity<Void> handleAsyncDisconnect(Exception e) {
        log.debug("SSE 连接已断开或超时：{}", e.getMessage());
        return ResponseEntity.noContent().build();
    }

    /** 兜底异常 → 500，日志记录完整堆栈 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error("服务器内部错误，请稍后重试"));
    }
}
