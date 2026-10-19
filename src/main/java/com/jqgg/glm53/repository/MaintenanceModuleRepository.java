package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.MaintenanceModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 数据库维护子模块数据访问接口。
 */
public interface MaintenanceModuleRepository extends JpaRepository<MaintenanceModule, Long> {

    List<MaintenanceModule> findAllByOrderBySortOrderAscIdAsc();

    Optional<MaintenanceModule> findByCode(String code);

    boolean existsByCode(String code);
}
