package com.jqgg.glm53.entity;

/**
 * 合同文件版本类型。
 */
public enum ContractFileType {

    /** 模板副本（保证模板被删除后仍可重新生成） */
    TEMPLATE_COPY,

    /** 系统生成的草稿 */
    GENERATED,

    /** 乙方签名图片 */
    SIGNATURE,

    /** 签署后的最终合同 */
    FINAL,

    /** 线下回签上传件 */
    UPLOADED
}
