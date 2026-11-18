package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.Contract;
import com.jqgg.glm53.entity.ContractStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 合同数据访问。
 */
public interface ContractRepository extends JpaRepository<Contract, Long> {

    List<Contract> findAllByOrderByIdDesc();

    List<Contract> findByReservationIdOrderByIdDesc(Long reservationId);

    List<Contract> findByReservationIdInAndStatusInOrderByIdDesc(
            Collection<Long> reservationIds, Collection<ContractStatus> statuses);

    Optional<Contract> findFirstByReservationIdAndStatusInOrderByIdDesc(
            Long reservationId, Collection<ContractStatus> statuses);

    /** 生成合同 / 上传回签件前锁定该预定的有效合同（当前读，绕过事务快照）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from Contract c
            where c.reservationId = :reservationId and c.status in :statuses
            order by c.id desc
            """)
    List<Contract> findOpenForUpdate(@Param("reservationId") Long reservationId,
                                     @Param("statuses") Collection<ContractStatus> statuses);

    /**
     * 条件更新为已签署：只有仍处于待签署时才成功。
     * 即使并发请求绕过了行锁 / 一级缓存，也只有一个请求能更新到 1 行。
     */
    @Modifying
    @Query("""
            update Contract c
               set c.status = :signedStatus,
                   c.signatureFilePath = :signaturePath,
                   c.currentFilePath = :currentPath,
                   c.currentFileName = :currentName,
                   c.signerName = :signerName,
                   c.signedAt = :signedAt,
                   c.signerIp = :signerIp,
                   c.updatedAt = :signedAt
             where c.id = :id and c.status = :pendingStatus
            """)
    int markSignedIfPending(@Param("id") Long id,
                            @Param("pendingStatus") ContractStatus pendingStatus,
                            @Param("signedStatus") ContractStatus signedStatus,
                            @Param("signaturePath") String signaturePath,
                            @Param("currentPath") String currentPath,
                            @Param("currentName") String currentName,
                            @Param("signerName") String signerName,
                            @Param("signedAt") LocalDateTime signedAt,
                            @Param("signerIp") String signerIp);

    Optional<Contract> findBySignToken(String signToken);

    /** 签署 / 上传回签件时锁定合同行，防止并发双签互相覆盖。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Contract c where c.signToken = :token")
    Optional<Contract> findBySignTokenForUpdate(@Param("token") String token);

    /** 管理员作废 / 结束 / 刷新链接时锁定合同行。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Contract c where c.id = :id")
    Optional<Contract> findByIdForUpdate(@Param("id") Long id);

    boolean existsByContractNo(String contractNo);

    long countByContractNoStartingWith(String prefix);

    /**
     * 查找结束日期已过、仍处于已签署状态的合同（定时任务自动完成并释放广告牌）。
     */
    @Query("""
            select c from Contract c
            where c.status = :status
              and c.reservationId in (
                  select r.id from Reservation r where r.endDate < :date
              )
            """)
    List<Contract> findExpiredSignedContracts(@Param("status") ContractStatus status,
                                             @Param("date") LocalDate date);
}
