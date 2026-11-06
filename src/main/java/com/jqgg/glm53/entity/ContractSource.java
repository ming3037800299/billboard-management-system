package com.jqgg.glm53.entity;

/**
 * 合同来源。
 */
public enum ContractSource {

    /** 系统按模板生成 */
    GENERATED,

    /** 线下已签合同上传 */
    UPLOADED;

    /** 返回该来源对应的中文标签。 */
    public String label() {
        return switch (this) {
            case GENERATED -> "系统生成";
            case UPLOADED -> "线下上传";
        };
    }
}
