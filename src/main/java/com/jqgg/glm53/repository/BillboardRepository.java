package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.Billboard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 广告牌数据访问接口。
 * 继承 JpaSpecificationExecutor 以支持路段 / 状态 / 价格 / 关键词等条件的动态组合查询。
 */
public interface BillboardRepository extends JpaRepository<Billboard, Long>, JpaSpecificationExecutor<Billboard> {

    /** 按编号精确查询（编号唯一索引覆盖全部记录，含软删除）。 */
    Optional<Billboard> findByCode(String code);

    /** 预定创建时锁定广告牌行，避免两个并发请求同时预定同一块牌。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Billboard b where b.id = :id")
    Optional<Billboard> findByIdForUpdate(@Param("id") Long id);
}
