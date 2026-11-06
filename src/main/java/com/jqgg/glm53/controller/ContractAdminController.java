package com.jqgg.glm53.controller;

import com.jqgg.glm53.dto.ApiResult;
import com.jqgg.glm53.dto.ContractDetailVO;
import com.jqgg.glm53.dto.ContractSettingsForm;
import com.jqgg.glm53.dto.ContractSettingsVO;
import com.jqgg.glm53.dto.ContractTemplateForm;
import com.jqgg.glm53.dto.ContractTemplateVO;
import com.jqgg.glm53.dto.ContractVO;
import com.jqgg.glm53.dto.GenerateContractForm;
import com.jqgg.glm53.dto.ReasonForm;
import com.jqgg.glm53.dto.ReservationForm;
import com.jqgg.glm53.dto.ReservationVO;
import com.jqgg.glm53.dto.UploadSignedForm;
import com.jqgg.glm53.entity.ContractSettings;
import com.jqgg.glm53.service.ContractService;
import com.jqgg.glm53.service.ContractSettingsService;
import com.jqgg.glm53.service.ContractTemplateService;
import com.jqgg.glm53.service.ContractService.FileDownload;
import com.jqgg.glm53.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 预定与合同后台接口（全部需要 X-Admin-Token，由 AdminAuthInterceptor 统一校验）：
 *
 * 签约设置：
 *   GET    /api/admin/contract-settings
 *   PUT    /api/admin/contract-settings
 *
 * 合同模板：
 *   GET    /api/admin/contract-templates
 *   POST   /api/admin/contract-templates
 *   POST   /api/admin/contract-templates/{id}/default
 *   DELETE /api/admin/contract-templates/{id}
 *   GET    /api/admin/contract-templates/{id}/file
 *
 * 预定：
 *   GET    /api/admin/reservations
 *   POST   /api/admin/reservations
 *   GET    /api/admin/reservations/{id}
 *   PUT    /api/admin/reservations/{id}
 *   POST   /api/admin/reservations/{id}/cancel
 *   POST   /api/admin/reservations/{id}/contracts
 *   POST   /api/admin/reservations/{id}/signed-contract
 *
 * 合同：
 *   GET    /api/admin/contracts
 *   GET    /api/admin/contracts/{id}
 *   POST   /api/admin/contracts/{id}/refresh-link
 *   POST   /api/admin/contracts/{id}/void
 *   POST   /api/admin/contracts/{id}/complete
 *   GET    /api/admin/contracts/{id}/download
 *   GET    /api/admin/contracts/{id}/files/{fileId}
 */
@RestController
@RequestMapping("/api/admin")
public class ContractAdminController {

    private final ContractSettingsService settingsService;
    private final ContractTemplateService templateService;
    private final ReservationService reservationService;
    private final ContractService contractService;

    public ContractAdminController(ContractSettingsService settingsService,
                                   ContractTemplateService templateService,
                                   ReservationService reservationService,
                                   ContractService contractService) {
        this.settingsService = settingsService;
        this.templateService = templateService;
        this.reservationService = reservationService;
        this.contractService = contractService;
    }

    // ------------------- 签约设置 -------------------

    @GetMapping("/contract-settings")
    public ApiResult<ContractSettingsVO> settings() {
        ContractSettings settings = settingsService.getOrCreate();
        return ApiResult.ok(settingsService.toVO(settings));
    }

    @PutMapping(value = "/contract-settings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ContractSettingsVO> saveSettings(@ModelAttribute ContractSettingsForm form) {
        ContractSettings settings = settingsService.update(form);
        return ApiResult.ok(settingsService.toVO(settings));
    }

    // ------------------- 合同模板 -------------------

    @GetMapping("/contract-templates")
    public ApiResult<List<ContractTemplateVO>> templates() {
        return ApiResult.ok(templateService.list());
    }

    @PostMapping(value = "/contract-templates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ContractTemplateVO> uploadTemplate(@ModelAttribute ContractTemplateForm form) {
        return ApiResult.ok(templateService.upload(form));
    }

    @PostMapping("/contract-templates/{id}/default")
    public ApiResult<ContractTemplateVO> setDefaultTemplate(@PathVariable Long id) {
        return ApiResult.ok(templateService.setDefault(id));
    }

    @DeleteMapping("/contract-templates/{id}")
    public ApiResult<Void> deleteTemplate(@PathVariable Long id) {
        templateService.delete(id);
        return ApiResult.ok();
    }

    @GetMapping("/contract-templates/{id}/file")
    public ResponseEntity<Resource> templateFile(@PathVariable Long id) {
        Path path = templateService.templateFile(id);
        Resource resource = new FileSystemResource(path);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(templateService.templateOriginalName(id), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    // ------------------- 预定 -------------------

    @GetMapping("/reservations")
    public ApiResult<List<ReservationVO>> reservations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long billboardId) {
        return ApiResult.ok(reservationService.list(status, keyword, billboardId));
    }

    @PostMapping("/reservations")
    public ApiResult<ReservationVO> createReservation(@Valid @RequestBody ReservationForm form) {
        return ApiResult.ok(reservationService.create(form));
    }

    @GetMapping("/reservations/{id}")
    public ApiResult<ReservationVO> reservation(@PathVariable Long id) {
        return ApiResult.ok(reservationService.detail(id));
    }

    @PutMapping("/reservations/{id}")
    public ApiResult<ReservationVO> updateReservation(@PathVariable Long id,
                                                      @Valid @RequestBody ReservationForm form) {
        return ApiResult.ok(reservationService.update(id, form));
    }

    @PostMapping("/reservations/{id}/cancel")
    public ApiResult<Void> cancelReservation(@PathVariable Long id,
                                             @RequestBody(required = false) ReasonForm form) {
        reservationService.cancel(id, form == null ? null : form.reason());
        return ApiResult.ok();
    }

    /** 为预定生成合同（可选指定模板）。 */
    @PostMapping("/reservations/{id}/contracts")
    public ApiResult<ContractDetailVO> generateContract(@PathVariable Long id,
                                                        @RequestBody(required = false) GenerateContractForm form) {
        return ApiResult.ok(contractService.generate(id, form == null ? null : form.templateId()));
    }

    /** 后台上传线下已签合同。 */
    @PostMapping(value = "/reservations/{id}/signed-contract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ContractDetailVO> uploadSignedContract(@PathVariable Long id,
                                                            @ModelAttribute UploadSignedForm form) {
        return ApiResult.ok(contractService.uploadSignedForReservation(id, form));
    }

    // ------------------- 合同 -------------------

    @GetMapping("/contracts")
    public ApiResult<List<ContractVO>> contracts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResult.ok(contractService.list(status, keyword));
    }

    @GetMapping("/contracts/{id}")
    public ApiResult<ContractDetailVO> contract(@PathVariable Long id) {
        return ApiResult.ok(contractService.detail(id));
    }

    @PostMapping("/contracts/{id}/refresh-link")
    public ApiResult<ContractDetailVO> refreshLink(@PathVariable Long id) {
        return ApiResult.ok(contractService.refreshLink(id));
    }

    @PostMapping("/contracts/{id}/void")
    public ApiResult<ContractDetailVO> voidContract(@PathVariable Long id,
                                                    @RequestBody(required = false) ReasonForm form) {
        return ApiResult.ok(contractService.voidContract(id, form == null ? null : form.reason()));
    }

    @PostMapping("/contracts/{id}/complete")
    public ApiResult<ContractDetailVO> completeContract(@PathVariable Long id) {
        return ApiResult.ok(contractService.complete(id));
    }

    @GetMapping("/contracts/{id}/download")
    public ResponseEntity<Resource> downloadContract(@PathVariable Long id) {
        return toResponse(contractService.adminCurrentFile(id));
    }

    @GetMapping("/contracts/{id}/files/{fileId}")
    public ResponseEntity<Resource> downloadContractFile(@PathVariable Long id,
                                                         @PathVariable Long fileId) {
        return toResponse(contractService.adminFile(id, fileId));
    }

    private ResponseEntity<Resource> toResponse(FileDownload file) {
        Resource resource = new FileSystemResource(file.path());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }
}
