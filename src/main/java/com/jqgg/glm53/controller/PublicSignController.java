package com.jqgg.glm53.controller;

import com.jqgg.glm53.dto.ApiResult;
import com.jqgg.glm53.dto.SignForm;
import com.jqgg.glm53.dto.SignInfoVO;
import com.jqgg.glm53.dto.UploadByTokenForm;
import com.jqgg.glm53.service.ContractService;
import com.jqgg.glm53.service.ContractService.FileDownload;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 乙方免登录签署接口（凭合同签署令牌访问，无需管理员令牌）：
 *   GET  /api/sign/{token}          合同摘要与可签署状态
 *   GET  /api/sign/{token}/file     下载/预览当前合同文件
 *   POST /api/sign/{token}/sign     手写签名或上传签名图片
 *   POST /api/sign/{token}/upload   上传线下已签合同
 */
@RestController
@RequestMapping("/api/sign")
public class PublicSignController {

    private final ContractService contractService;

    public PublicSignController(ContractService contractService) {
        this.contractService = contractService;
    }

    @GetMapping("/{token}")
    public ApiResult<SignInfoVO> info(@PathVariable String token) {
        return ApiResult.ok(contractService.publicInfo(token));
    }

    @GetMapping("/{token}/file")
    public ResponseEntity<Resource> file(@PathVariable String token) {
        FileDownload file = contractService.publicFile(token);
        Resource resource = new FileSystemResource(file.path());
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    @PostMapping(value = "/{token}/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<SignInfoVO> sign(@PathVariable String token,
                                      @ModelAttribute SignForm form,
                                      HttpServletRequest request) {
        return ApiResult.ok(contractService.sign(token, form, clientIp(request)));
    }

    @PostMapping(value = "/{token}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<SignInfoVO> upload(@PathVariable String token,
                                        @ModelAttribute UploadByTokenForm form,
                                        HttpServletRequest request) {
        return ApiResult.ok(contractService.uploadSigned(token, form, clientIp(request)));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
