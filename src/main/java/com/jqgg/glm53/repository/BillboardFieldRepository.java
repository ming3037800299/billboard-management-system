package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.BillboardField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 广告牌扩展字段定义数据访问接口。
 */
public interface BillboardFieldRepository extends JpaRepository<BillboardField, Long> {

    List<BillboardField> findAllByOrderBySortOrderAscIdAsc();

    List<BillboardField> findByModuleIdOrderBySortOrderAscIdAsc(Long moduleId);

    Optional<BillboardField> findByFieldName(String fieldName);

    boolean existsByFieldName(String fieldName);

    boolean existsByModuleId(Long moduleId);

    long countByModuleId(Long moduleId);
}
