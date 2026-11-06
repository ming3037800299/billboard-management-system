package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.ContractFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 合同文件版本数据访问。
 */
public interface ContractFileRepository extends JpaRepository<ContractFile, Long> {

    List<ContractFile> findByContractIdOrderByCreatedAtAsc(Long contractId);
}
