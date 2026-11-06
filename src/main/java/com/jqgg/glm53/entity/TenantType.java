package com.jqgg.glm53.entity;

/**
 * 乙方（承租方）主体类型。
 */
public enum TenantType {

    /** 个人 */
    INDIVIDUAL,

    /** 企业 */
    COMPANY;

    /** 返回该类型对应的中文标签。 */
    public String label() {
        return switch (this) {
            case INDIVIDUAL -> "个人";
            case COMPANY -> "企业";
        };
    }
}
