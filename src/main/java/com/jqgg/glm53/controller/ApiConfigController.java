package com.jqgg.glm53.controller;

import com.jqgg.glm53.dto.ApiConfigForm;
import com.jqgg.glm53.dto.ApiConfigVO;
import com.jqgg.glm53.dto.ApiResult;
import com.jqgg.glm53.dto.ApiTestRequest;
import com.jqgg.glm53.dto.ApiTestResultVO;
import com.jqgg.glm53.entity.ApiType;
import com.jqgg.glm53.service.ApiConfigService;
import com.jqgg.glm53.service.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台 API 管理接口（均需 X-Admin-Token）：
 *   GET    /api/admin/apis              配置列表（可按 type 过滤，Key 脱敏）
 *   POST   /api/admin/apis              新增配置
 *   PUT    /api/admin/apis/{id}         编辑配置（apiKey 留空表示不修改）
 *   DELETE /api/admin/apis/{id}         删除配置
 *   POST   /api/admin/apis/{id}/default 设为同类默认配置
 *   POST   /api/admin/apis/{id}/test    测试已保存配置
 *   POST   /api/admin/apis/test         测试表单中的配置（保存前预检）
 *
 * <p>向量模型配置发生变化后，会自动在后台重建知识库索引（异步）。</p>
 */
@RestController
@RequestMapping("/api/admin/apis")
public class ApiConfigController {

    private final ApiConfigService apiConfigService;
    private final KnowledgeService knowledgeService;

    public ApiConfigController(ApiConfigService apiConfigService,
                               KnowledgeService knowledgeService) {
        this.apiConfigService = apiConfigService;
        this.knowledgeService = knowledgeService;
    }

    /** 配置列表：type 取值 EMBEDDING / LLM / MAP / OTHER。 */
    @GetMapping
    public ApiResult<List<ApiConfigVO>> list(@RequestParam(required = false) String type) {
        return ApiResult.ok(apiConfigService.list(type));
    }

    /** 新增配置。 */
    @PostMapping
    public ApiResult<ApiConfigVO> create(@Valid @RequestBody ApiConfigForm form) {
        ApiConfigVO saved = apiConfigService.create(form);
        reindexIfEmbedding(saved.type());
        return ApiResult.ok(saved);
    }

    /** 编辑配置。 */
    @PutMapping("/{id}")
    public ApiResult<ApiConfigVO> update(@PathVariable Long id,
                                         @Valid @RequestBody ApiConfigForm form) {
        ApiType oldType = apiConfigService.findType(id);
        ApiConfigVO saved = apiConfigService.update(id, form);
        if (oldType == ApiType.EMBEDDING || "EMBEDDING".equals(saved.type())) {
            knowledgeService.reindexForConfigChangeAsync();
        }
        return ApiResult.ok(saved);
    }

    /** 删除配置。 */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        ApiType type = apiConfigService.findType(id);
        apiConfigService.delete(id);
        if (type == ApiType.EMBEDDING) {
            knowledgeService.reindexForConfigChangeAsync();
        }
        return ApiResult.ok();
    }

    /** 设为同类默认配置。 */
    @PostMapping("/{id}/default")
    public ApiResult<ApiConfigVO> setDefault(@PathVariable Long id) {
        ApiConfigVO saved = apiConfigService.setDefault(id);
        if ("EMBEDDING".equals(saved.type())) {
            knowledgeService.reindexForConfigChangeAsync();
        }
        return ApiResult.ok(saved);
    }

    /** 测试已保存的配置。 */
    @PostMapping("/{id}/test")
    public ApiResult<ApiTestResultVO> testSaved(@PathVariable Long id) {
        return ApiResult.ok(apiConfigService.testSaved(id));
    }

    /** 保存前测试当前表单配置。 */
    @PostMapping("/test")
    public ApiResult<ApiTestResultVO> test(@Valid @RequestBody ApiTestRequest request) {
        return ApiResult.ok(apiConfigService.test(request));
    }

    private void reindexIfEmbedding(String type) {
        if ("EMBEDDING".equals(type)) {
            knowledgeService.reindexForConfigChangeAsync();
        }
    }
}
