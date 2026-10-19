package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.ApiConfig;
import com.jqgg.glm53.entity.ApiType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * API 配置数据访问。
 */
public interface ApiConfigRepository extends JpaRepository<ApiConfig, Long> {

    /** 全部配置：按类型、默认优先、ID 升序，后台列表直接使用。 */
    List<ApiConfig> findAllByOrderByTypeAscPreferredDescIdAsc();

    /** 指定类型：默认优先、ID 升序。 */
    @Query("select c from ApiConfig c where c.type = :type order by c.preferred desc, c.id asc")
    List<ApiConfig> findByTypeOrdered(@Param("type") ApiType type);

    /** 指定类型中已启用的配置：默认优先、ID 升序。 */
    @Query("select c from ApiConfig c where c.type = :type and c.enabled = true order by c.preferred desc, c.id asc")
    List<ApiConfig> findEnabledByType(@Param("type") ApiType type);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByTypeAndEnabledTrue(ApiType type);
}
