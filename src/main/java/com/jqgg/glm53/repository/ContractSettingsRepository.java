package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.ContractSettings;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 甲方签约主体配置数据访问。
 */
public interface ContractSettingsRepository extends JpaRepository<ContractSettings, Long> {
}
