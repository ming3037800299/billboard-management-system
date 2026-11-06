package com.jqgg.glm53.entity;

/**
 * 预定状态。
 */
public enum ReservationStatus {

    /** 已预定（等待签约） */
    CONFIRMED,

    /** 已签约（合同已签署） */
    SIGNED,

    /** 已完成（合同到期或提前结束） */
    COMPLETED,

    /** 已取消 */
    CANCELLED;

    /** 返回该状态对应的中文标签。 */
    public String label() {
        return switch (this) {
            case CONFIRMED -> "已预定";
            case SIGNED -> "已签约";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
        };
    }
}
