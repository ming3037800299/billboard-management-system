package com.jqgg.glm53.dto;

import java.time.LocalDateTime;

/**
 * 甲方签约主体配置视图（含签章图片 Data URL，便于后台直接预览）。
 */
public record ContractSettingsVO(
        String partyAName,
        String partyACreditCode,
        String partyAAddress,
        String partyALegalRep,
        String partyAContact,
        String partyAPhone,
        String partyABankName,
        String partyABankAccount,
        int signLinkTtlDays,
        boolean hasSeal,
        String sealDataUrl,
        LocalDateTime updatedAt) {
}
