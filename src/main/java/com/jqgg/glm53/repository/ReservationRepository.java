package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.Reservation;
import com.jqgg.glm53.entity.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 预定记录数据访问。
 */
public interface ReservationRepository extends JpaRepository<Reservation, Long>, JpaSpecificationExecutor<Reservation> {

    List<Reservation> findAllByOrderByIdDesc();

    boolean existsByBillboardIdAndStatusIn(Long billboardId, Collection<ReservationStatus> statuses);

    List<Reservation> findByBillboardIdAndStatusInOrderByIdDesc(Long billboardId, Collection<ReservationStatus> statuses);

    long countByReservationNoStartingWith(String prefix);

    boolean existsByReservationNo(String reservationNo);

    /** 生成合同 / 签约 / 状态流转时锁定预定行。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    /** 同一广告牌是否存在与给定日期区间重叠的未取消预定（历史 / 当前均算）。 */
    @Query("""
            select (count(r) > 0) from Reservation r
            where r.billboardId = :billboardId
              and r.status <> :cancelled
              and r.startDate <= :endDate
              and r.endDate >= :startDate
              and (:excludeId is null or r.id <> :excludeId)
            """)
    boolean existsOverlapping(@Param("billboardId") Long billboardId,
                              @Param("startDate") LocalDate startDate,
                              @Param("endDate") LocalDate endDate,
                              @Param("excludeId") Long excludeId,
                              @Param("cancelled") ReservationStatus cancelled);
}
