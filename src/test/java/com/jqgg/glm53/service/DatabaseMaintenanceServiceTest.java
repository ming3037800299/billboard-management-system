package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.DbColumnVO;
import com.jqgg.glm53.dto.DbTableVO;
import com.jqgg.glm53.entity.Billboard;
import com.jqgg.glm53.entity.BillboardStatus;
import com.jqgg.glm53.repository.BillboardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据库维护检索测试（H2 内存库）：
 * 表结构读取、字段名检索、表内数据内容检索与概览。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jqggdbmaint;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.rag.enabled=false",
})
class DatabaseMaintenanceServiceTest {

    @Autowired
    private DatabaseMaintenanceService service;

    @Autowired
    private BillboardRepository billboardRepository;

    private Billboard sample(String code) {
        Billboard b = new Billboard();
        b.setName("数据库检索测试广告牌");
        b.setCode(code);
        b.setRoad("京沪高速G2");
        b.setPrice(new BigDecimal("20000"));
        b.setPriceUnit("元/月");
        b.setStatus(BillboardStatus.AVAILABLE);
        return b;
    }

    @Test
    void 表结构包含业务表与维护表() {
        var tables = service.tables(null);
        assertThat(tables).extracting(DbTableVO::name)
                .anyMatch("billboard"::equalsIgnoreCase)
                .anyMatch("billboard_field"::equalsIgnoreCase)
                .anyMatch("sys_maintenance_module"::equalsIgnoreCase);

        DbTableVO billboard = tables.stream()
                .filter(t -> t.name().equalsIgnoreCase("billboard"))
                .findFirst()
                .orElseThrow();
        assertThat(billboard.columns()).extracting(DbColumnVO::name)
                .anyMatch("price_note"::equalsIgnoreCase)
                .anyMatch("code"::equalsIgnoreCase);
    }

    @Test
    void 按字段名检索可定位到表与字段() {
        var result = service.search("price_note", false);
        assertThat(result.columnHits()).anySatisfy(hit -> {
            assertThat(hit.table()).isEqualToIgnoringCase("billboard");
            assertThat(hit.column()).isEqualToIgnoringCase("price_note");
            assertThat(hit.matchType()).contains("字段名");
        });
    }

    @Test
    void 检索表内数据内容可定位具体行() {
        Billboard saved = billboardRepository.save(sample("DBS-001"));
        var result = service.search("DBS-001", true);
        assertThat(result.includeData()).isTrue();
        assertThat(result.dataHits()).anySatisfy(hit -> {
            assertThat(hit.table()).isEqualToIgnoringCase("billboard");
            assertThat(hit.column()).isEqualToIgnoringCase("code");
            assertThat(hit.sampleValue()).contains("DBS-001");
            assertThat(hit.rowRef().toLowerCase(Locale.ROOT)).contains("id=" + saved.getId());
        });
    }

    @Test
    void 概览返回数据库与DDL状态() {
        var overview = service.overview();
        assertThat(overview.tableCount()).isGreaterThanOrEqualTo(5);
        assertThat(overview.columnCount()).isGreaterThan(10);
        assertThat(overview.product()).isNotBlank();
        assertThat(overview.ddlAllowed()).isTrue();
    }
}
