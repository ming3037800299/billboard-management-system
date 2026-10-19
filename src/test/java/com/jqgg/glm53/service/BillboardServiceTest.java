package com.jqgg.glm53.service;

import com.jqgg.glm53.entity.Billboard;
import com.jqgg.glm53.entity.BillboardStatus;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.exception.NotFoundException;
import com.jqgg.glm53.repository.BillboardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BillboardService 核心业务测试（H2 内存库 + 每用例事务回滚）。
 * 覆盖：列表筛选 / 排序 / 关键词、编号唯一、编辑、软删除与恢复、去重路段。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jqggsvc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.rag.enabled=false",
})
@Transactional
class BillboardServiceTest {

    @Autowired
    private BillboardService service;

    @Autowired
    private BillboardRepository repository;

    /** 构造一条示例广告牌数据。 */
    private Billboard sample(String code, String road, BillboardStatus status, String price) {
        Billboard b = new Billboard();
        b.setName("测试广告牌-" + code);
        b.setCode(code);
        b.setRoad(road);
        b.setSection("测试段 K1+000");
        b.setLongitude(new BigDecimal("115.000000"));
        b.setLatitude(new BigDecimal("38.000000"));
        b.setImages("[\"/img/samples/bb-01.svg\"]");
        b.setPrice(new BigDecimal(price));
        b.setPriceUnit("元/月");
        b.setStatus(status);
        return b;
    }

    @Test
    void 新增并查询列表() {
        service.create(sample("T-001", "京沪高速G2", BillboardStatus.AVAILABLE, "20000"));
        service.create(sample("T-002", "沈海高速G15", BillboardStatus.RENTED, "30000"));

        List<Billboard> all = service.list(new BillboardService.Query(null, null, null, null, null, null, false));
        assertThat(all).hasSize(2);
        // 默认按 id 倒序：后创建的在前
        assertThat(all.get(0).getCode()).isEqualTo("T-002");
    }

    @Test
    void 按路段与状态筛选() {
        service.create(sample("T-001", "京沪高速G2", BillboardStatus.AVAILABLE, "20000"));
        service.create(sample("T-002", "京沪高速G2", BillboardStatus.RENTED, "30000"));
        service.create(sample("T-003", "沈海高速G15", BillboardStatus.AVAILABLE, "25000"));

        List<Billboard> byRoad = service.list(new BillboardService.Query("京沪高速G2", null, null, null, null, null, false));
        assertThat(byRoad).extracting(Billboard::getCode).containsExactlyInAnyOrder("T-001", "T-002");

        List<Billboard> byRoadAndStatus = service.list(
                new BillboardService.Query("京沪高速G2", "RENTED", null, null, null, null, false));
        assertThat(byRoadAndStatus).extracting(Billboard::getCode).containsExactly("T-002");

        // 非法状态参数按未筛选处理，不抛异常
        assertThat(service.list(new BillboardService.Query(null, "NOT_A_STATUS", null, null, null, null, false))).hasSize(3);
    }

    @Test
    void 按价格区间筛选与排序() {
        service.create(sample("T-001", "A路", BillboardStatus.AVAILABLE, "10000"));
        service.create(sample("T-002", "A路", BillboardStatus.AVAILABLE, "20000"));
        service.create(sample("T-003", "A路", BillboardStatus.AVAILABLE, "30000"));

        List<Billboard> range = service.list(
                new BillboardService.Query(null, null, new BigDecimal("12000"), new BigDecimal("25000"), null, null, false));
        assertThat(range).extracting(Billboard::getCode).containsExactly("T-002");

        List<Billboard> asc = service.list(
                new BillboardService.Query(null, null, null, null, null, "price_asc", false));
        assertThat(asc).extracting(Billboard::getPrice).isSorted();

        List<Billboard> desc = service.list(
                new BillboardService.Query(null, null, null, null, null, "price_desc", false));
        assertThat(desc).extracting(Billboard::getPrice)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void 关键词模糊匹配名称编号路段位置() {
        Billboard b = sample("T-001", "京沪高速G2", BillboardStatus.AVAILABLE, "20000");
        b.setSection("保定南段 K1123+500");
        service.create(b);

        assertThat(service.list(new BillboardService.Query(null, null, null, null, "T-001", null, false))).hasSize(1);
        assertThat(service.list(new BillboardService.Query(null, null, null, null, "京沪", null, false))).hasSize(1);
        assertThat(service.list(new BillboardService.Query(null, null, null, null, "K1123", null, false))).hasSize(1);
        assertThat(service.list(new BillboardService.Query(null, null, null, null, "不存在的关键词", null, false))).isEmpty();
    }

    @Test
    void 编号重复时拒绝新增与修改() {
        service.create(sample("T-001", "A路", BillboardStatus.AVAILABLE, "10000"));

        assertThatThrownBy(() -> service.create(sample("T-001", "B路", BillboardStatus.AVAILABLE, "20000")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("编号已存在");

        // 修改自身编号不变不应报错
        Billboard saved = repository.findByCode("T-001").orElseThrow();
        Billboard changes = sample("T-001", "A路", BillboardStatus.PAUSED, "15000");
        service.update(saved.getId(), changes);

        // 修改为他人编号应报错
        service.create(sample("T-002", "B路", BillboardStatus.AVAILABLE, "20000"));
        Billboard other = repository.findByCode("T-002").orElseThrow();
        assertThatThrownBy(() -> service.update(other.getId(), sample("T-001", "B路", BillboardStatus.AVAILABLE, "1")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 编辑覆盖全部字段() {
        Billboard saved = service.create(sample("T-001", "A路", BillboardStatus.AVAILABLE, "10000"));

        Billboard changes = sample("T-009", "B路", BillboardStatus.RENTED, "55500");
        changes.setName("改名后的广告牌");
        changes.setRoad("B路");
        changes.setPriceNote("含电费");
        Billboard updated = service.update(saved.getId(), changes);

        assertThat(updated.getName()).isEqualTo("改名后的广告牌");
        assertThat(updated.getCode()).isEqualTo("T-009");
        assertThat(updated.getRoad()).isEqualTo("B路");
        assertThat(updated.getStatus()).isEqualTo(BillboardStatus.RENTED);
        assertThat(updated.getPrice()).isEqualByComparingTo("55500");
        assertThat(updated.getPriceNote()).isEqualTo("含电费");
    }

    @Test
    void 软删除后前台不可见_后台可见_可恢复() {
        Billboard saved = service.create(sample("T-001", "A路", BillboardStatus.AVAILABLE, "10000"));
        Long id = saved.getId();

        service.delete(id);

        // 前台列表与详情均不可见
        assertThat(service.list(new BillboardService.Query(null, null, null, null, null, null, false))).isEmpty();
        assertThatThrownBy(() -> service.detail(id, false)).isInstanceOf(NotFoundException.class);

        // 后台含已删除可见
        List<Billboard> withDeleted = service.list(
                new BillboardService.Query(null, null, null, null, null, null, true));
        assertThat(withDeleted).hasSize(1);
        assertThat(service.detail(id, true).isDeleted()).isTrue();

        // 恢复后前台可见
        service.restore(id);
        assertThat(service.list(new BillboardService.Query(null, null, null, null, null, null, false))).hasSize(1);
        assertThat(service.detail(id, false).isDeleted()).isFalse();
    }

    @Test
    void 去重路段列表() {
        service.create(sample("T-001", "京沪高速G2", BillboardStatus.AVAILABLE, "10000"));
        service.create(sample("T-002", "京沪高速G2", BillboardStatus.RENTED, "20000"));
        service.create(sample("T-003", "沈海高速G15", BillboardStatus.AVAILABLE, "30000"));

        assertThat(service.distinctRoads()).containsExactly("京沪高速G2", "沈海高速G15");
    }
}
