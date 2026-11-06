package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.ContractTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 合同模板数据访问。
 */
public interface ContractTemplateRepository extends JpaRepository<ContractTemplate, Long> {

    List<ContractTemplate> findAllByOrderByDefaultTemplateDescIdDesc();

    List<ContractTemplate> findByEnabledTrueOrderByDefaultTemplateDescIdDesc();

    Optional<ContractTemplate> findFirstByDefaultTemplateTrueAndEnabledTrue();

    Optional<ContractTemplate> findByIdAndEnabledTrue(Long id);

    long countByEnabledTrue();

    long countByDefaultTemplateTrueAndEnabledTrue();
}
