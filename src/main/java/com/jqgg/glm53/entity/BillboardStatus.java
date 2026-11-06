package com.jqgg.glm53.entity;

/**
 * 广告牌租赁状态。
 * 前端展示时映射为中文：AVAILABLE 可租赁 / RESERVED 已预定 / RENTED 已租赁 / PAUSED 暂停出租。
 */
public enum BillboardStatus {

    /** 可租赁 */
    AVAILABLE,

    /** 已预定（存在未完成的预定，其他客户不能再次预定） */
    RESERVED,

    /** 已租赁 */
    RENTED,

    /** 暂停出租 */
    PAUSED;

    /** 返回该状态对应的中文标签。 */
    public String label() {
        return switch (this) {
            case AVAILABLE -> "可租赁";
            case RESERVED -> "已预定";
            case RENTED -> "已租赁";
            case PAUSED -> "暂停出租";
        };
    }
}
