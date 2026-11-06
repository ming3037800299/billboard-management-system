package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.ContractEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 合同操作日志数据访问。
 */
public interface ContractEventRepository extends JpaRepository<ContractEvent, Long> {

    List<ContractEvent> findByContractIdOrderByCreatedAtDesc(Long contractId);

    List<ContractEvent> findByReservationIdOrderByCreatedAtDesc(Long reservationId);
}
