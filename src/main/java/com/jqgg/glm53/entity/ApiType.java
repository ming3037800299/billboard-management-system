package com.jqgg.glm53.entity;

/**
 * 后台可管理的接口类型。
 * 每种类型同一时间最多有一个“默认配置”生效；没有数据库配置时回退 application.yml。
 */
public enum ApiType {

    /** 向量模型：知识库索引与问题向量化 */
    EMBEDDING("向量模型"),

    /** 检索增强生成（RAG）问答模型 */
    LLM("检索生成模型"),

    /** 地图服务（当前前台使用腾讯位置服务 AK） */
    MAP("地图服务"),

    /** 其他扩展接口：仅做地址 / Key 托管与连通性测试 */
    OTHER("其他接口");

    private final String label;

    ApiType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
