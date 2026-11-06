package com.jqgg.glm53.dto;

import java.util.List;

/**
 * 合同详情：合同信息 + 预定信息 + 文件版本 + 操作日志 + 可选模板。
 */
public record ContractDetailVO(
        ContractVO contract,
        ReservationVO reservation,
        List<ContractFileVO> files,
        List<ContractEventVO> events,
        List<ContractTemplateVO> templates) {
}
