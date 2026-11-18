package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.ContractTemplateForm;
import com.jqgg.glm53.dto.ContractTemplateVO;
import com.jqgg.glm53.entity.ContractTemplate;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.exception.NotFoundException;
import com.jqgg.glm53.repository.ContractTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 合同 Word 模板管理。
 * 首次访问时自动生成内置默认模板；上传自定义模板时校验签名/签章图片占位符。
 */
@Service
public class ContractTemplateService {

    private final ContractTemplateRepository repository;
    private final ContractFileStorageService storage;
    private final DocxContractService docxService;

    public ContractTemplateService(ContractTemplateRepository repository,
                                   ContractFileStorageService storage,
                                   DocxContractService docxService) {
        this.repository = repository;
        this.storage = storage;
        this.docxService = docxService;
    }

    /** 全部模板（含已停用），首次调用时确保默认模板存在。 */
    @Transactional
    public List<ContractTemplateVO> list() {
        ensureDefaultTemplate();
        return repository.findAllByOrderByDefaultTemplateDescIdDesc().stream()
                .map(this::toVO)
                .toList();
    }

    /** 可用模板（启用状态）。 */
    @Transactional
    public List<ContractTemplateVO> enabledTemplates() {
        ensureDefaultTemplate();
        return repository.findByEnabledTrueOrderByDefaultTemplateDescIdDesc().stream()
                .map(this::toVO)
                .toList();
    }

    /** 取生成合同所用模板：指定 ID 优先，否则使用默认模板。 */
    @Transactional
    public ContractTemplate resolveTemplate(Long templateId) {
        if (templateId != null) {
            return repository.findByIdAndEnabledTrue(templateId)
                    .orElseThrow(() -> new BadRequestException("指定的合同模板不存在或已停用"));
        }
        ensureDefaultTemplate();
        return repository.findFirstByDefaultTemplateTrueAndEnabledTrue()
                .orElseThrow(() -> new BadRequestException("请先在「签约设置」中设置默认合同模板"));
    }

    /** 上传自定义 Word 模板。 */
    @Transactional
    public ContractTemplateVO upload(ContractTemplateForm form) {
        if (form == null || form.getFile() == null || form.getFile().isEmpty()) {
            throw new BadRequestException("请选择要上传的 .docx 合同模板");
        }
        String name = form.getName() == null || form.getName().isBlank()
                ? "自定义合同模板" : form.getName().trim();
        if (name.length() > 200) {
            throw new BadRequestException("模板名称过长（最多 200 字）");
        }
        // 先扫描上传内容校验占位符，校验通过后再落盘，避免无效模板留下孤儿文件
        Set<String> tags = docxService.scanPlaceholders(form.getFile());
        requirePlaceholder(tags, "{{@甲方签章}}", "甲方签章");
        requirePlaceholder(tags, "{{@乙方签名}}", "乙方签名");
        String filePath = storage.storeDocx(form.getFile(), "templates/" + monthDir());
        try {
            ContractTemplate template = new ContractTemplate();
            template.setName(name);
            template.setFilePath(filePath);
            template.setOriginalName(storage.originalName(form.getFile(), "contract-template.docx"));
            template.setPlaceholders(String.join("\n", tags));
            template.setEnabled(true);
            if (form.isSetDefault() || repository.countByDefaultTemplateTrueAndEnabledTrue() == 0) {
                clearOtherDefaults(null);
                template.setDefaultTemplate(true);
            }
            return toVO(repository.save(template));
        } catch (RuntimeException e) {
            storage.deleteQuietly(filePath);
            throw e;
        }
    }

    /** 设为默认模板。 */
    @Transactional
    public ContractTemplateVO setDefault(Long id) {
        ContractTemplate template = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("合同模板不存在"));
        if (!template.isEnabled()) {
            throw new BadRequestException("已停用的模板不能设为默认");
        }
        clearOtherDefaults(id);
        template.setDefaultTemplate(true);
        return toVO(repository.save(template));
    }

    /** 删除（软删除）模板；默认模板需先切换到其他模板。 */
    @Transactional
    public void delete(Long id) {
        ContractTemplate template = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("合同模板不存在"));
        if (template.isDefaultTemplate()) {
            ContractTemplate fallback = repository.findByEnabledTrueOrderByDefaultTemplateDescIdDesc().stream()
                    .filter(t -> !t.getId().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException("至少需要保留一个可用的合同模板，不能删除唯一模板"));
            fallback.setDefaultTemplate(true);
            repository.save(fallback);
        }
        template.setDefaultTemplate(false);
        template.setEnabled(false);
        repository.save(template);
    }

    /** 模板文件路径（后台下载/预览用）。 */
    public Path templateFile(Long id) {
        ContractTemplate template = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("合同模板不存在"));
        return storage.resolve(template.getFilePath());
    }

    /** 模板原文件名。 */
    public String templateOriginalName(Long id) {
        return repository.findById(id)
                .map(t -> t.getOriginalName() == null ? t.getName() + ".docx" : t.getOriginalName())
                .orElse("contract-template.docx");
    }

    /** 把模板复制一份到合同目录，保证模板后续被替代/删除后仍可重现合同。 */
    public String copyTemplateToContract(ContractTemplate template, String contractNo) {
        String subdir = "contracts/" + monthDir() + "/" + contractNo;
        return storage.copy(template.getFilePath(), subdir, "template.docx");
    }

    /** 模板占位符列表（原始标签）。 */
    public List<String> placeholdersOf(ContractTemplate template) {
        if (template.getPlaceholders() == null || template.getPlaceholders().isBlank()) {
            return List.of();
        }
        return Arrays.stream(template.getPlaceholders().split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 确保存在可用的默认模板；默认模板文件丢失时自动重建。 */
    @Transactional
    public ContractTemplate ensureDefaultTemplate() {
        ContractTemplate current = repository.findFirstByDefaultTemplateTrueAndEnabledTrue().orElse(null);
        if (current != null && storage.exists(current.getFilePath())) {
            return current;
        }
        if (current != null) {
            // 文件丢失：重新生成内置模板并覆盖该记录。
            byte[] bytes = DefaultContractTemplateFactory.build();
            String filePath = storage.storeBytes(bytes, "templates/" + monthDir(), "docx");
            current.setFilePath(filePath);
            current.setPlaceholders(String.join("\n", docxService.scanPlaceholders(storage.resolve(filePath))));
            current.setName("默认广告牌租赁合同（系统内置）");
            current.setOriginalName("default-contract-template.docx");
            current.setEnabled(true);
            current.setDefaultTemplate(true);
            return repository.save(current);
        }

        // 没有默认模板：若有其他启用模板，选一个设为默认。
        List<ContractTemplate> enabled = repository.findByEnabledTrueOrderByDefaultTemplateDescIdDesc();
        if (!enabled.isEmpty()) {
            ContractTemplate fallback = enabled.get(0);
            clearOtherDefaults(fallback.getId());
            fallback.setDefaultTemplate(true);
            return repository.save(fallback);
        }

        byte[] bytes = DefaultContractTemplateFactory.build();
        String filePath = storage.storeBytes(bytes, "templates/" + monthDir(), "docx");
        ContractTemplate template = new ContractTemplate();
        template.setName("默认广告牌租赁合同（系统内置）");
        template.setFilePath(filePath);
        template.setOriginalName("default-contract-template.docx");
        template.setPlaceholders(String.join("\n", docxService.scanPlaceholders(storage.resolve(filePath))));
        template.setDefaultTemplate(true);
        template.setEnabled(true);
        template.setCreatedAt(LocalDateTime.now());
        return repository.save(template);
    }

    public ContractTemplateVO toVO(ContractTemplate template) {
        return new ContractTemplateVO(
                template.getId(),
                template.getName(),
                template.getOriginalName(),
                template.isDefaultTemplate(),
                template.isEnabled(),
                placeholdersOf(template),
                "/api/admin/contract-templates/" + template.getId() + "/file",
                template.getCreatedAt());
    }

    private void clearOtherDefaults(Long keepId) {
        List<ContractTemplate> defaults = repository.findAllByOrderByDefaultTemplateDescIdDesc();
        for (ContractTemplate t : defaults) {
            if (t.isDefaultTemplate() && (keepId == null || !t.getId().equals(keepId))) {
                t.setDefaultTemplate(false);
                repository.save(t);
            }
        }
    }

    private void requirePlaceholder(Set<String> tags, String tag, String label) {
        if (!tags.contains(tag)) {
            throw new BadRequestException("模板缺少图片占位符 " + tag + "（" + label
                    + "），请在 Word 模板中保留该占位符后重新上传");
        }
    }

    private String monthDir() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    /** 仅用于调试：将占位符集合转为可读列表。 */
    private List<String> toList(Set<String> tags) {
        return new ArrayList<>(tags);
    }
}
