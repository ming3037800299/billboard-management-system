package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.ContractSummaryVO;
import com.jqgg.glm53.dto.ReservationForm;
import com.jqgg.glm53.dto.ReservationVO;
import com.jqgg.glm53.entity.ActorType;
import com.jqgg.glm53.entity.Billboard;
import com.jqgg.glm53.entity.BillboardStatus;
import com.jqgg.glm53.entity.Contract;
import com.jqgg.glm53.entity.ContractEvent;
import com.jqgg.glm53.entity.ContractStatus;
import com.jqgg.glm53.entity.Reservation;
import com.jqgg.glm53.entity.ReservationStatus;
import com.jqgg.glm53.entity.TenantType;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.exception.NotFoundException;
import com.jqgg.glm53.repository.BillboardRepository;
import com.jqgg.glm53.repository.ContractEventRepository;
import com.jqgg.glm53.repository.ContractRepository;
import com.jqgg.glm53.repository.ReservationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 预定业务服务：
 *   - 创建预定即锁定广告牌（状态改为已预定）；
 *   - 修改预定仅允许在未生成合同前；
 *   - 取消预定自动作废待签合同并释放广告牌；
 *   - 签约 / 完成 / 作废时同步预定与广告牌状态。
 */
@Service
public class ReservationService {

    /** 仍占用广告牌的状态 */
    public static final Set<ReservationStatus> ACTIVE_STATUSES =
            EnumSet.of(ReservationStatus.CONFIRMED, ReservationStatus.SIGNED);

    /** 仍有效的合同状态 */
    public static final Set<ContractStatus> OPEN_CONTRACT_STATUSES =
            EnumSet.of(ContractStatus.PENDING_SIGN, ContractStatus.SIGNED);

    private final ReservationRepository repository;
    private final BillboardRepository billboardRepository;
    private final ContractRepository contractRepository;
    private final ContractEventRepository eventRepository;
    private final SequenceService sequenceService;
    private final EntityManager entityManager;

    public ReservationService(ReservationRepository repository,
                              BillboardRepository billboardRepository,
                              ContractRepository contractRepository,
                              ContractEventRepository eventRepository,
                              SequenceService sequenceService,
                              EntityManager entityManager) {
        this.repository = repository;
        this.billboardRepository = billboardRepository;
        this.contractRepository = contractRepository;
        this.eventRepository = eventRepository;
        this.sequenceService = sequenceService;
        this.entityManager = entityManager;
    }

    /** 列表筛选。 */
    @Transactional(readOnly = true)
    public List<ReservationVO> list(String status, String keyword, Long billboardId) {
        Specification<Reservation> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                try {
                    predicates.add(cb.equal(root.get("status"),
                            ReservationStatus.valueOf(status.trim().toUpperCase())));
                } catch (IllegalArgumentException ignored) {
                    // 非法状态按未筛选处理
                }
            }
            if (billboardId != null) {
                predicates.add(cb.equal(root.get("billboardId"), billboardId));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("reservationNo"), like),
                        cb.like(root.get("tenantName"), like),
                        cb.like(root.get("billboardName"), like),
                        cb.like(root.get("billboardCode"), like),
                        cb.like(root.get("billboardRoad"), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<Reservation> reservations = repository.findAll(spec, Sort.by(Sort.Direction.DESC, "id"));
        Map<Long, Contract> activeContracts = loadActiveContracts(
                reservations.stream().map(Reservation::getId).toList());
        return reservations.stream()
                .map(r -> toVO(r, activeContracts.get(r.getId())))
                .toList();
    }

    /** 详情。 */
    @Transactional(readOnly = true)
    public ReservationVO detail(Long id) {
        Reservation reservation = requireReservation(id);
        Contract contract = contractRepository
                .findFirstByReservationIdAndStatusInOrderByIdDesc(id, OPEN_CONTRACT_STATUSES)
                .orElse(null);
        return toVO(reservation, contract);
    }

    /** 创建预定并锁定广告牌。 */
    @Transactional
    public ReservationVO create(ReservationForm form) {
        validateDates(form.startDate(), form.endDate());
        Billboard billboard = billboardRepository.findByIdForUpdate(form.billboardId())
                .orElseThrow(() -> new NotFoundException("广告牌不存在"));
        if (billboard.isDeleted()) {
            throw new BadRequestException("该广告牌已删除，不能预定");
        }
        if (billboard.getStatus() != BillboardStatus.AVAILABLE) {
            throw new BadRequestException("该广告牌当前状态为「" + billboard.getStatus().label()
                    + "」，不能新增预定");
        }
        if (repository.existsOverlapping(billboard.getId(), form.startDate(), form.endDate(),
                null, ReservationStatus.CANCELLED)) {
            throw new BadRequestException("该广告牌在所选投放期内已有预定，请调整投放日期");
        }

        Reservation reservation = new Reservation();
        reservation.setReservationNo(nextReservationNo());
        reservation.setBillboardId(billboard.getId());
        snapshotBillboard(reservation, billboard);
        applyForm(reservation, form, billboard);
        reservation.setStatus(ReservationStatus.CONFIRMED);

        billboard.setStatus(BillboardStatus.RESERVED);
        billboardRepository.save(billboard);
        Reservation saved = repository.save(reservation);
        addEvent(null, saved.getId(), "RESERVATION_CREATED", ActorType.ADMIN,
                "创建预定：" + saved.getReservationNo());
        return toVO(saved, null);
    }

    /** 修改预定（仅未生成合同前）。 */
    @Transactional
    public ReservationVO update(Long id, ReservationForm form) {
        validateDates(form.startDate(), form.endDate());
        Reservation preview = requireReservation(id);
        // 与创建预定保持一致的加锁顺序：广告牌 → 预定
        Billboard billboard = billboardRepository.findByIdForUpdate(preview.getBillboardId())
                .orElseThrow(() -> new NotFoundException("广告牌不存在"));
        Reservation reservation = requireReservationForUpdate(id);
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new BadRequestException("当前预定状态为「" + reservation.getStatus().label()
                    + "」，不能修改");
        }
        if (!reservation.getBillboardId().equals(form.billboardId())) {
            throw new BadRequestException("预定创建后不能更换广告牌，请取消后重新预定");
        }
        Contract openContract = contractRepository
                .findFirstByReservationIdAndStatusInOrderByIdDesc(id, OPEN_CONTRACT_STATUSES)
                .orElse(null);
        if (openContract != null) {
            throw new BadRequestException("该预定已生成合同，请先作废合同后再修改预定信息");
        }
        if (repository.existsOverlapping(reservation.getBillboardId(), form.startDate(), form.endDate(),
                id, ReservationStatus.CANCELLED)) {
            throw new BadRequestException("该广告牌在所选投放期内已有其他预定，请调整投放日期");
        }
        applyForm(reservation, form, billboard);
        Reservation saved = repository.save(reservation);
        addEvent(null, saved.getId(), "RESERVATION_UPDATED", ActorType.ADMIN,
                "修改预定：" + saved.getReservationNo());
        return toVO(saved, null);
    }

    /** 取消预定（仅未签约），自动作废待签合同并释放广告牌。 */
    @Transactional
    public void cancel(Long id, String reason) {
        Reservation preview = requireReservation(id);
        // 与创建 / 修改保持一致：先锁广告牌，再锁预定
        billboardRepository.findByIdForUpdate(preview.getBillboardId())
                .orElseThrow(() -> new NotFoundException("广告牌不存在"));
        Reservation reservation = requireReservationForUpdate(id);
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new BadRequestException("只有「已预定」状态的记录可以取消");
        }
        Contract openContract = contractRepository
                .findFirstByReservationIdAndStatusInOrderByIdDesc(id, OPEN_CONTRACT_STATUSES)
                .orElse(null);
        if (openContract != null) {
            if (openContract.getStatus() == ContractStatus.SIGNED) {
                throw new BadRequestException("合同已签署，请使用「作废合同」结束该预定");
            }
            openContract.setStatus(ContractStatus.VOID);
            openContract.setVoidReason("预定已取消：" + safeReason(reason));
            openContract.setSignTokenRevoked(true);
            contractRepository.save(openContract);
            addEvent(openContract.getId(), id, "CONTRACT_VOIDED", ActorType.ADMIN,
                    "预定取消，系统自动作废待签合同");
        }
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(LocalDateTime.now());
        reservation.setCancelReason(safeReason(reason));
        repository.save(reservation);
        releaseBillboard(reservation.getBillboardId(), BillboardStatus.RESERVED);
        addEvent(null, id, "RESERVATION_CANCELLED", ActorType.ADMIN,
                "取消预定：" + safeReason(reason));
    }

    /** 标记预定为已签约，并把广告牌改为已租赁。重复调用按幂等处理。 */
    @Transactional
    public void markSigned(Reservation reservation) {
        Reservation current = requireReservationForUpdate(reservation.getId());
        if (current.getStatus() == ReservationStatus.SIGNED) {
            return;
        }
        if (current.getStatus() != ReservationStatus.CONFIRMED) {
            throw new BadRequestException("当前预定状态为「" + current.getStatus().label()
                    + "」，不能标记为已签约");
        }
        current.setStatus(ReservationStatus.SIGNED);
        repository.save(current);
        billboardRepository.findByIdForUpdate(current.getBillboardId()).ifPresent(b -> {
            if (b.getStatus() != BillboardStatus.RENTED) {
                b.setStatus(BillboardStatus.RENTED);
                billboardRepository.save(b);
            }
        });
    }

    /** 标记预定为已完成，并释放广告牌。重复调用按幂等处理。 */
    @Transactional
    public void markCompleted(Reservation reservation) {
        Reservation current = requireReservationForUpdate(reservation.getId());
        if (current.getStatus() == ReservationStatus.COMPLETED) {
            return;
        }
        if (current.getStatus() != ReservationStatus.SIGNED) {
            throw new BadRequestException("当前预定状态为「" + current.getStatus().label()
                    + "」，不能标记为已完成");
        }
        current.setStatus(ReservationStatus.COMPLETED);
        repository.save(current);
        releaseBillboard(current.getBillboardId(), BillboardStatus.RENTED);
    }

    /** 已签合同作废时取消预定，并释放广告牌。重复调用按幂等处理。 */
    @Transactional
    public void cancelFromContract(Reservation reservation, String reason) {
        Reservation current = requireReservationForUpdate(reservation.getId());
        if (current.getStatus() == ReservationStatus.CANCELLED) {
            return;
        }
        if (current.getStatus() != ReservationStatus.SIGNED) {
            throw new BadRequestException("当前预定状态为「" + current.getStatus().label()
                    + "」，不能按合同作废取消");
        }
        current.setStatus(ReservationStatus.CANCELLED);
        current.setCancelledAt(LocalDateTime.now());
        current.setCancelReason(safeReason(reason));
        repository.save(current);
        releaseBillboard(current.getBillboardId(), BillboardStatus.RENTED);
    }

    /** 查询并校验预定存在。 */
    @Transactional(readOnly = true)
    public Reservation requireReservation(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("预定记录不存在（ID=" + id + "）"));
    }

    /** 查询并锁定预定行（合同生成 / 签约 / 状态流转使用）。 */
    @Transactional
    public Reservation requireReservationForUpdate(Long id) {
        Reservation reservation = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("预定记录不存在（ID=" + id + "）"));
        // 同一事务里可能已经加载过该实体，refresh 以数据库当前值为准
        entityManager.refresh(reservation);
        return reservation;
    }

    /** 锁定广告牌行，与预定行组成统一的加锁顺序（广告牌 → 预定 → 合同）。 */
    @Transactional
    public void lockBillboard(Long billboardId) {
        billboardRepository.findByIdForUpdate(billboardId)
                .orElseThrow(() -> new NotFoundException("广告牌不存在（ID=" + billboardId + "）"));
    }

    /** 查询预定的有效合同（待签署 / 已签署），没有则返回 null。 */
    @Transactional
    public Contract findOpenContract(Long reservationId) {
        List<Contract> open = contractRepository
                .findOpenForUpdate(reservationId, OPEN_CONTRACT_STATUSES);
        return open.isEmpty() ? null : open.get(0);
    }

    /** 组装视图对象。 */
    public ReservationVO toVO(Reservation r, Contract contract) {
        ContractSummaryVO summary = null;
        if (contract != null) {
            boolean expired = contract.getSignTokenExpiresAt() != null
                    && contract.getSignTokenExpiresAt().isBefore(LocalDateTime.now());
            boolean canSign = contract.getStatus() == ContractStatus.PENDING_SIGN
                    && !contract.isSignTokenRevoked()
                    && !expired
                    && contract.getSignToken() != null;
            String signingPath = contract.getSignToken() == null || contract.isSignTokenRevoked()
                    ? null : "/sign/" + contract.getSignToken();
            summary = new ContractSummaryVO(
                    contract.getId(),
                    contract.getContractNo(),
                    contract.getStatus().name(),
                    contract.getStatus().label(),
                    contract.getSource().name(),
                    contract.getSource().label(),
                    signingPath,
                    contract.getSignTokenExpiresAt(),
                    contract.getSignedAt(),
                    canSign);
        }
        return new ReservationVO(
                r.getId(),
                r.getReservationNo(),
                r.getBillboardId(),
                r.getBillboardCode(),
                r.getBillboardName(),
                r.getBillboardRoad(),
                r.getBillboardSection(),
                r.getBillboardAddress(),
                r.getBillboardSize(),
                r.getBillboardMaterial(),
                r.getBillboardVisibleArea(),
                r.getBillboardPrice(),
                r.getBillboardPriceUnit(),
                r.getBillboardPriceNote(),
                r.getTenantType().name(),
                r.getTenantType().label(),
                r.getTenantName(),
                r.getTenantIdNumber(),
                r.getTenantAddress(),
                r.getTenantContact(),
                r.getTenantPhone(),
                r.getTenantEmail(),
                r.getAdContent(),
                r.getStartDate(),
                r.getEndDate(),
                r.getLeaseMonths(),
                leaseDuration(r.getLeaseMonths()),
                r.getTotalAmount(),
                r.getDepositAmount(),
                r.getPaymentTerms(),
                r.getSpecialTerms(),
                r.getRemark(),
                r.getStatus().name(),
                r.getStatus().label(),
                r.getCancelledAt(),
                r.getCancelReason(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                summary);
    }

    // ------------------- 内部方法 -------------------

    private void applyForm(Reservation reservation, ReservationForm form, Billboard billboard) {
        if (form.tenantName() == null || form.tenantName().isBlank()) {
            throw new BadRequestException("请填写乙方姓名或公司名称");
        }
        reservation.setTenantType(parseTenantType(form.tenantType()));
        reservation.setTenantName(form.tenantName().trim());
        reservation.setTenantIdNumber(trimToNull(form.tenantIdNumber()));
        reservation.setTenantAddress(trimToNull(form.tenantAddress()));
        reservation.setTenantContact(trimToNull(form.tenantContact()));
        reservation.setTenantPhone(trimToNull(form.tenantPhone()));
        reservation.setTenantEmail(trimToNull(form.tenantEmail()));
        reservation.setAdContent(trimToNull(form.adContent()));
        reservation.setStartDate(form.startDate());
        reservation.setEndDate(form.endDate());
        int months = computeLeaseMonths(form.startDate(), form.endDate());
        reservation.setLeaseMonths(months);
        BigDecimal suggested = billboard.getPrice() == null
                ? BigDecimal.ZERO : suggestedAmount(billboard.getPrice(), billboard.getPriceUnit(), months);
        reservation.setTotalAmount(nonNegative(
                form.totalAmount() != null ? form.totalAmount() : suggested, "合同金额"));
        reservation.setDepositAmount(nonNegative(
                form.depositAmount() != null ? form.depositAmount() : BigDecimal.ZERO, "定金金额"));
        reservation.setPaymentTerms(trimToNull(form.paymentTerms()));
        reservation.setSpecialTerms(trimToNull(form.specialTerms()));
        reservation.setRemark(trimToNull(form.remark()));
    }

    private void snapshotBillboard(Reservation r, Billboard b) {
        r.setBillboardCode(b.getCode());
        r.setBillboardName(b.getName());
        r.setBillboardRoad(b.getRoad());
        r.setBillboardSection(b.getSection());
        r.setBillboardAddress(b.getAddress());
        r.setBillboardSize(b.getSize());
        r.setBillboardMaterial(b.getMaterial());
        r.setBillboardVisibleArea(b.getVisibleArea());
        r.setBillboardPrice(b.getPrice());
        r.setBillboardPriceUnit(b.getPriceUnit());
        r.setBillboardPriceNote(b.getPriceNote());
    }

    private void validateDates(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BadRequestException("请选择投放起止日期");
        }
        if (end.isBefore(start)) {
            throw new BadRequestException("投放结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(start, end) > 3650) {
            throw new BadRequestException("租赁期限过长（最多 10 年）");
        }
    }

    private int computeLeaseMonths(LocalDate start, LocalDate end) {
        // 按自然月计算：结束日次日为独占边界，不足一个月向上取整。
        LocalDate exclusiveEnd = end.plusDays(1);
        long months = ChronoUnit.MONTHS.between(start, exclusiveEnd);
        if (start.plusMonths(months).isBefore(exclusiveEnd)) {
            months++;
        }
        return Math.max(1, (int) months);
    }

    private BigDecimal suggestedAmount(BigDecimal price, String priceUnit, int months) {
        BigDecimal amount;
        if ("元/年".equals(priceUnit)) {
            int years = Math.max(1, (int) Math.ceil(months / 12.0));
            amount = price.multiply(BigDecimal.valueOf(years));
        } else {
            amount = price.multiply(BigDecimal.valueOf(months));
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nonNegative(BigDecimal value, String label) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.signum() < 0) {
            throw new BadRequestException(label + "不能为负数");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private TenantType parseTenantType(String raw) {
        if (raw == null || raw.isBlank()) {
            return TenantType.INDIVIDUAL;
        }
        try {
            return TenantType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("乙方类型不合法：" + raw);
        }
    }

    private String leaseDuration(int months) {
        if (months >= 12 && months % 12 == 0) {
            return (months / 12) + "年";
        }
        return months + "个月";
    }

    private Map<Long, Contract> loadActiveContracts(Collection<Long> reservationIds) {
        if (reservationIds == null || reservationIds.isEmpty()) {
            return Map.of();
        }
        return contractRepository
                .findByReservationIdInAndStatusInOrderByIdDesc(reservationIds, OPEN_CONTRACT_STATUSES)
                .stream()
                .collect(Collectors.toMap(Contract::getReservationId, c -> c, (a, b) -> a));
    }

    private void releaseBillboard(Long billboardId, BillboardStatus expectedStatus) {
        // 锁住广告牌行后再判断是否还有活动预定，避免“刚释放又来了新预定”时误改状态
        Billboard billboard = billboardRepository.findByIdForUpdate(billboardId).orElse(null);
        if (billboard == null) {
            return;
        }
        entityManager.refresh(billboard);
        if (billboard.getStatus() != expectedStatus) {
            return;
        }
        if (repository.existsByBillboardIdAndStatusIn(billboardId, ACTIVE_STATUSES)) {
            return;
        }
        billboard.setStatus(BillboardStatus.AVAILABLE);
        billboardRepository.save(billboard);
    }

    private String nextReservationNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long seq = sequenceService.next("reservation-" + day);
        return "YD-" + day + "-" + String.format("%04d", seq);
    }

    private void addEvent(Long contractId, Long reservationId, String action,
                          ActorType actor, String detail) {
        ContractEvent event = new ContractEvent();
        event.setContractId(contractId);
        event.setReservationId(reservationId);
        event.setAction(action);
        event.setActorType(actor);
        event.setDetail(detail);
        eventRepository.save(event);
    }

    private String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "未填写原因";
        }
        String value = reason.trim();
        return value.length() > 480 ? value.substring(0, 480) : value;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
