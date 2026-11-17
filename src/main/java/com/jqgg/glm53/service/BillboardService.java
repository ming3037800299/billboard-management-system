package com.jqgg.glm53.service;

import com.jqgg.glm53.entity.Billboard;
import com.jqgg.glm53.entity.BillboardStatus;
import com.jqgg.glm53.entity.ReservationStatus;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.exception.NotFoundException;
import com.jqgg.glm53.repository.BillboardRepository;
import com.jqgg.glm53.repository.ReservationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 广告牌业务服务：查询（动态筛选 + 排序）、新增、编辑、软删除、恢复。
 * 所有前台 / 后台的数据操作均经由本类完成，保证业务规则一致。
 * 数据库维护模块新增的扩展字段值，与核心字段在同一事务内保存。
 */
@Service
public class BillboardService {

    private final BillboardRepository repository;
    private final BillboardFieldService fieldService;
    private final ReservationRepository reservationRepository;
    private final EntityManager entityManager;

    public BillboardService(BillboardRepository repository, BillboardFieldService fieldService,
                            ReservationRepository reservationRepository, EntityManager entityManager) {
        this.repository = repository;
        this.fieldService = fieldService;
        this.reservationRepository = reservationRepository;
        this.entityManager = entityManager;
    }

    /** 广告牌查询条件。 */
    public record Query(
            String road,             // 所在路段（精确匹配）
            String status,           // 租赁状态（AVAILABLE/RENTED/PAUSED）
            BigDecimal minPrice,     // 最低单价
            BigDecimal maxPrice,     // 最高单价
            String keyword,          // 关键词（名称/编号/路段/位置模糊匹配）
            String sort,             // 排序：newest（默认）/ price_asc / price_desc
            boolean includeDeleted   // 是否包含软删除数据（后台"查看已删除"用）
    ) {
    }

    /**
     * 条件查询广告牌列表（默认按创建时间倒序）。
     */
    @Transactional(readOnly = true)
    public List<Billboard> list(Query q) {
        Specification<Billboard> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!q.includeDeleted()) {
                predicates.add(cb.isFalse(root.get("deleted")));
            }
            if (notBlank(q.road())) {
                predicates.add(cb.equal(root.get("road"), q.road().trim()));
            }
            parseStatus(q.status()).ifPresent(st -> predicates.add(cb.equal(root.get("status"), st)));
            if (q.minPrice() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), q.minPrice()));
            }
            if (q.maxPrice() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("price"), q.maxPrice()));
            }
            if (notBlank(q.keyword())) {
                String like = "%" + q.keyword().trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("name"), like),
                        cb.like(root.get("code"), like),
                        cb.like(root.get("road"), like),
                        cb.like(root.get("section"), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return repository.findAll(spec, resolveSort(q.sort()));
    }

    /** 查询单个广告牌详情（默认只查未删除；后台可查看已删除数据）。 */
    @Transactional(readOnly = true)
    public Billboard detail(Long id, boolean includeDeleted) {
        Billboard b = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("广告牌不存在（ID=" + id + "）"));
        if (b.isDeleted() && !includeDeleted) {
            throw new NotFoundException("广告牌不存在（ID=" + id + "）");
        }
        return b;
    }

    /**
     * 新增广告牌（编号唯一性校验）。
     */
    @Transactional
    public Billboard create(Billboard b) {
        return create(b, Map.of());
    }

    /**
     * 新增广告牌并保存扩展字段值。
     * 扩展值先校验，核心字段与扩展列在同一事务内提交。
     */
    @Transactional
    public Billboard create(Billboard b, Map<String, Object> extraValues) {
        checkCodeUnique(b.getCode(), null);
        Map<String, Object> normalized = fieldService.validateAndNormalize(extraValues);
        Billboard saved = repository.save(b);
        fieldService.writeValues(saved.getId(), normalized);
        // @DynamicInsert 时数据库会补默认值，refresh 让接口响应与实际落库一致
        entityManager.flush();
        entityManager.refresh(saved);
        return saved;
    }

    /**
     * 编辑广告牌：以传入实体的非空字段覆盖既有记录。
     */
    @Transactional
    public Billboard update(Long id, Billboard changes) {
        return update(id, changes, Map.of());
    }

    /**
     * 编辑广告牌并保存扩展字段值。
     */
    @Transactional
    public Billboard update(Long id, Billboard changes, Map<String, Object> extraValues) {
        Billboard existing = detail(id, true);
        checkCodeUnique(changes.getCode(), id);
        guardReservedStatus(existing, changes.getStatus());
        Map<String, Object> normalized = fieldService.validateAndNormalize(extraValues);
        existing.setName(changes.getName());
        existing.setCode(changes.getCode());
        existing.setRoad(changes.getRoad());
        existing.setSection(changes.getSection());
        existing.setAddress(changes.getAddress());
        existing.setLongitude(changes.getLongitude());
        existing.setLatitude(changes.getLatitude());
        existing.setImages(changes.getImages());
        existing.setImageMeta(changes.getImageMeta());
        existing.setPrice(changes.getPrice());
        existing.setPriceUnit(changes.getPriceUnit());
        existing.setPriceNote(changes.getPriceNote());
        existing.setSize(changes.getSize());
        existing.setMaterial(changes.getMaterial());
        existing.setVisibleArea(changes.getVisibleArea());
        existing.setAudience(changes.getAudience());
        existing.setStatus(changes.getStatus());
        existing.setDescription(changes.getDescription());
        Billboard saved = repository.save(existing);
        fieldService.writeValues(saved.getId(), normalized);
        entityManager.flush();
        entityManager.refresh(saved);
        return saved;
    }

    /** 软删除广告牌（保留数据，可通过 restore 恢复）。 */
    @Transactional
    public void delete(Long id) {
        Billboard existing = detail(id, true);
        if (reservationRepository.existsByBillboardIdAndStatusIn(id,
                java.util.EnumSet.of(ReservationStatus.CONFIRMED, ReservationStatus.SIGNED))) {
            throw new BadRequestException("该广告牌存在未完成的预定或已签约合同，请先处理预定/合同后再删除");
        }
        existing.setDeleted(true);
        repository.save(existing);
    }

    /**
     * 预定/合同占用期间不允许通过广告牌编辑表单绕过业务流程修改状态。
     */
    private void guardReservedStatus(Billboard existing, BillboardStatus newStatus) {
        if (newStatus == null || newStatus == existing.getStatus()) {
            return;
        }
        boolean hasActive = reservationRepository.existsByBillboardIdAndStatusIn(existing.getId(),
                java.util.EnumSet.of(ReservationStatus.CONFIRMED, ReservationStatus.SIGNED));
        if (hasActive && (existing.getStatus() == BillboardStatus.RESERVED
                || existing.getStatus() == BillboardStatus.RENTED)) {
            throw new BadRequestException("该广告牌存在未完成的预定或已签约合同，"
                    + "请先在「预定与合同」中取消、作废或结束，再修改租赁状态");
        }
    }

    /** 恢复软删除的广告牌。 */
    @Transactional
    public Billboard restore(Long id) {
        Billboard existing = detail(id, true);
        if (!existing.isDeleted()) {
            throw new BadRequestException("该广告牌未被删除，无需恢复");
        }
        existing.setDeleted(false);
        return repository.save(existing);
    }

    /** 全部未删除数据中的去重路段列表（前台筛选下拉数据源）。 */
    @Transactional(readOnly = true)
    public List<String> distinctRoads() {
        return repository.findAll((root, query, cb) -> cb.isFalse(root.get("deleted"))).stream()
                .map(Billboard::getRoad)
                .filter(r -> r != null && !r.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /** 编号唯一性校验（编辑时排除自身；软删除记录同样占用编号，与数据库唯一索引一致）。 */
    private void checkCodeUnique(String code, Long excludeId) {
        repository.findByCode(code).stream()
                .filter(b -> excludeId == null || !excludeId.equals(b.getId()))
                .findAny()
                .ifPresent(b -> {
                    throw new BadRequestException("广告牌编号已存在：" + code + "（请更换编号）");
                });
    }

    /** 解析排序参数。 */
    private Sort resolveSort(String sort) {
        if (sort == null) {
            return Sort.by(Sort.Direction.DESC, "id");
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "price");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "price");
            default -> Sort.by(Sort.Direction.DESC, "id");
        };
    }

    /** 状态参数容错：仅接受合法枚举名，否则按未筛选处理。 */
    private Optional<BillboardStatus> parseStatus(String status) {
        if (!notBlank(status)) {
            return Optional.empty();
        }
        try {
            return Optional.of(BillboardStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
