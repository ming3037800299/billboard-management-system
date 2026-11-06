package com.jqgg.glm53.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新增 / 编辑预定的请求体。
 */
public record ReservationForm(
        @NotNull(message = "请选择广告牌") Long billboardId,
        String tenantType,
        @NotBlank(message = "请填写乙方姓名或公司名称") @Size(max = 200, message = "乙方名称过长") String tenantName,
        @Size(max = 100, message = "证件号码过长") String tenantIdNumber,
        @Size(max = 500, message = "乙方地址过长") String tenantAddress,
        @Size(max = 100, message = "联系人过长") String tenantContact,
        @Size(max = 50, message = "联系电话过长") String tenantPhone,
        @Size(max = 200, message = "邮箱过长") String tenantEmail,
        String adContent,
        @NotNull(message = "请选择投放开始日期") LocalDate startDate,
        @NotNull(message = "请选择投放结束日期") LocalDate endDate,
        BigDecimal totalAmount,
        BigDecimal depositAmount,
        @Size(max = 500, message = "付款方式过长") String paymentTerms,
        String specialTerms,
        @Size(max = 500, message = "备注过长") String remark) {
}
