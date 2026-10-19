package com.jqgg.glm53.dto;

import java.util.List;

/**
 * 按子模块分组的广告牌扩展字段（后台表单渲染用）。
 */
public record BillboardFieldGroupVO(
        Long moduleId,
        String moduleName,
        String moduleCode,
        String moduleType,
        String moduleTypeLabel,
        String moduleDescription,
        int sortOrder,
        List<BillboardFieldVO> fields) {
}
