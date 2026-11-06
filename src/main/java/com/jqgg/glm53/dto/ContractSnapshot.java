package com.jqgg.glm53.dto;

import java.util.Map;

/**
 * 合同生成时的字段快照。
 * 签署时使用同一份快照重新渲染，避免后台修改甲方信息或预定内容影响已生成合同。
 */
public record ContractSnapshot(
        Map<String, String> fields,
        String sealPath,
        String templatePath,
        String templateName) {
}
