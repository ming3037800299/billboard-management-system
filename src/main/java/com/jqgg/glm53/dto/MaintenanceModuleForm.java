package com.jqgg.glm53.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 编辑数据库维护子模块的请求体。
 * 类型在创建后不可修改，避免已存在的扩展字段失去归属。
 */
public class MaintenanceModuleForm {

    @NotBlank(message = "请填写子模块名称")
    @Size(max = 100, message = "子模块名称不能超过 100 字")
    private String name;

    /** 模块标识：小写字母开头，可含数字/下划线 */
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,49}$",
            message = "模块标识需为小写字母开头，可包含数字和下划线（2-50 位）")
    private String code;

    /** SEARCH / SCHEMA / BILLBOARD_FIELD / BILLBOARD_ATTRIBUTE */
    private String type;

    @Size(max = 500, message = "说明不能超过 500 字")
    private String description;

    private Integer sortOrder;

    private Boolean enabled;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
