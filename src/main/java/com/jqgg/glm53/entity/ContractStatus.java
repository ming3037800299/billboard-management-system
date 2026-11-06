package com.jqgg.glm53.entity;

/**
 * 合同状态。
 */
public enum ContractStatus {

    /** 待乙方签署 */
    PENDING_SIGN,

    /** 已签署 */
    SIGNED,

    /** 已完成 */
    COMPLETED,

    /** 已作废 */
    VOID;

    /** 返回该状态对应的中文标签。 */
    public String label() {
        return switch (this) {
            case PENDING_SIGN -> "待签署";
            case SIGNED -> "已签署";
            case COMPLETED -> "已完成";
            case VOID -> "已作废";
        };
    }
}
