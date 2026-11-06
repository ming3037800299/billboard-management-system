package com.jqgg.glm53.entity;

/**
 * 合同操作方类型。
 */
public enum ActorType {

    /** 管理员 */
    ADMIN,

    /** 乙方（通过签署链接操作） */
    TENANT,

    /** 系统（定时任务等） */
    SYSTEM
}
