package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 广告牌知识库文档数据访问接口。
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
}
