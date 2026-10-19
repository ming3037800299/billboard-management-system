package com.jqgg.glm53.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增 / 编辑 API 配置的表单。
 *
 * <p>编辑时 apiKey 留空表示“保持原 Key 不变”；如需清空，传 clearApiKey=true。</p>
 */
public record ApiConfigForm(

        @NotBlank(message = "请填写配置名称")
        @Size(max = 100, message = "配置名称不能超过 100 个字符")
        String name,

        @NotBlank(message = "请选择接口类型")
        String type,

        @Size(max = 30, message = "服务商标识过长")
        String provider,

        @Size(max = 500, message = "接口地址不能超过 500 个字符")
        String baseUrl,

        @Size(max = 1000, message = "API Key 不能超过 1000 个字符")
        String apiKey,

        /** 编辑时是否清空已保存的 Key */
        Boolean clearApiKey,

        @Size(max = 100, message = "模型名称不能超过 100 个字符")
        String model,

        @Min(value = 1, message = "最大 token 数至少为 1")
        @Max(value = 32000, message = "最大 token 数不能超过 32000")
        Integer maxTokens,

        @DecimalMin(value = "0.0", message = "温度不能小于 0")
        @DecimalMax(value = "2.0", message = "温度不能大于 2")
        Double temperature,

        @Min(value = 1, message = "超时时间至少为 1 秒")
        @Max(value = 300, message = "超时时间不能超过 300 秒")
        Integer timeoutSeconds,

        Boolean enabled,

        Boolean isDefault,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
}
