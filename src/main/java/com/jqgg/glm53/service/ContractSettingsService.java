package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.ContractSettingsForm;
import com.jqgg.glm53.dto.ContractSettingsVO;
import com.jqgg.glm53.entity.ContractSettings;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.repository.ContractSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;

/**
 * 甲方签约主体配置服务。
 * 首次访问时自动生成示例主体与示例签章，便于预览；正式使用请在后台替换。
 */
@Service
public class ContractSettingsService {

    private static final long SETTINGS_ID = 1L;

    /** 示例签章的固定路径：用户主动移除后，文件仍在，不会再次自动生成。 */
    private static final String SAMPLE_SEAL_PATH = "seals/sample-seal.png";

    private final ContractSettingsRepository repository;
    private final ContractFileStorageService storage;

    public ContractSettingsService(ContractSettingsRepository repository,
                                   ContractFileStorageService storage) {
        this.repository = repository;
        this.storage = storage;
    }

    /** 读取配置；不存在时创建示例配置。 */
    @Transactional
    public ContractSettings getOrCreate() {
        ContractSettings settings = repository.findById(SETTINGS_ID).orElseGet(this::createSampleSettings);
        // SQL 初始化脚本会先插入一条示例主体（没有签章文件）；预览时补一张示例签章。
        // 一旦甲方名称被替换，就不再自动生成，避免覆盖真实配置。
        if (settings.getSealPath() == null
                && settings.getPartyAName() != null
                && settings.getPartyAName().contains("示例")
                && !storage.exists(SAMPLE_SEAL_PATH)) {
            settings.setSealPath(storage.storeBytesAt(
                    ContractImageFactory.sampleSeal(settings.getPartyAName()), "seals", "sample-seal.png"));
            settings.setUpdatedAt(LocalDateTime.now());
            settings = repository.save(settings);
        }
        return settings;
    }

    /** 后台保存配置。 */
    @Transactional
    public ContractSettings update(ContractSettingsForm form) {
        if (form == null || form.getPartyAName() == null || form.getPartyAName().isBlank()) {
            throw new BadRequestException("请填写甲方名称");
        }
        ContractSettings settings = getOrCreate();
        settings.setPartyAName(form.getPartyAName().trim());
        settings.setPartyACreditCode(trimToNull(form.getPartyACreditCode()));
        settings.setPartyAAddress(trimToNull(form.getPartyAAddress()));
        settings.setPartyALegalRep(trimToNull(form.getPartyALegalRep()));
        settings.setPartyAContact(trimToNull(form.getPartyAContact()));
        settings.setPartyAPhone(trimToNull(form.getPartyAPhone()));
        settings.setPartyABankName(trimToNull(form.getPartyABankName()));
        settings.setPartyABankAccount(trimToNull(form.getPartyABankAccount()));

        int ttl = form.getSignLinkTtlDays() == null ? 7 : form.getSignLinkTtlDays();
        settings.setSignLinkTtlDays(Math.max(1, Math.min(365, ttl)));

        if (form.isRemoveSeal()) {
            settings.setSealPath(null);
        } else if (form.getSeal() != null && !form.getSeal().isEmpty()) {
            settings.setSealPath(storage.storeImage(form.getSeal(), "seals"));
        }
        settings.setUpdatedAt(LocalDateTime.now());
        return repository.save(settings);
    }

    /** 合同生成前检查：甲方名称与签章必须已配置。 */
    public void requireReadyForContract() {
        ContractSettings settings = getOrCreate();
        if (settings.getPartyAName() == null || settings.getPartyAName().isBlank()) {
            throw new BadRequestException("请先在「签约设置」中填写甲方名称");
        }
        if (settings.getSealPath() == null || !storage.exists(settings.getSealPath())) {
            throw new BadRequestException("请先在「签约设置」中上传甲方签章图片");
        }
    }

    /** 甲方签章 PNG/JPG 字节；未配置时返回 null。 */
    public byte[] sealBytes() {
        ContractSettings settings = getOrCreate();
        if (settings.getSealPath() == null || !storage.exists(settings.getSealPath())) {
            return null;
        }
        return storage.read(settings.getSealPath());
    }

    /** 签署链接有效期（天），限制在 1~365。 */
    public int signLinkTtlDays() {
        return Math.max(1, Math.min(365, getOrCreate().getSignLinkTtlDays()));
    }

    /** 转视图对象，签章图片以 Data URL 返回便于后台预览。 */
    public ContractSettingsVO toVO(ContractSettings settings) {
        String sealDataUrl = null;
        boolean hasSeal = settings.getSealPath() != null && storage.exists(settings.getSealPath());
        if (hasSeal) {
            byte[] bytes = storage.read(settings.getSealPath());
            String ext = storage.extensionOf(settings.getSealPath());
            String mime = "png".equals(ext) ? "image/png" : "image/jpeg";
            sealDataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }
        return new ContractSettingsVO(
                settings.getPartyAName(),
                settings.getPartyACreditCode(),
                settings.getPartyAAddress(),
                settings.getPartyALegalRep(),
                settings.getPartyAContact(),
                settings.getPartyAPhone(),
                settings.getPartyABankName(),
                settings.getPartyABankAccount(),
                settings.getSignLinkTtlDays(),
                hasSeal,
                sealDataUrl,
                settings.getUpdatedAt());
    }

    private ContractSettings createSampleSettings() {
        ContractSettings settings = new ContractSettings();
        settings.setId(SETTINGS_ID);
        settings.setPartyAName("示例广告牌运营有限公司（请替换）");
        settings.setPartyACreditCode("91310000MA1FL0000X");
        settings.setPartyAAddress("示例省示例市示例大道 1 号");
        settings.setPartyALegalRep("张三");
        settings.setPartyAContact("李四");
        settings.setPartyAPhone("13800000000");
        settings.setPartyABankName("示例银行示例支行");
        settings.setPartyABankAccount("6222000000000000");
        settings.setSignLinkTtlDays(7);
        settings.setUpdatedAt(LocalDateTime.now());
        // 生成明确标注“示例签章”的占位章，方便预览流程；正式使用请替换为真实签章。
        byte[] sampleSeal = ContractImageFactory.sampleSeal(settings.getPartyAName());
        settings.setSealPath(storage.storeBytesAt(sampleSeal, "seals", "sample-seal.png"));
        return repository.save(settings);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** 仅用于日志或调试：去除可能的多余空格。 */
    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
