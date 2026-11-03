package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.DbDatabaseVO;
import com.jqgg.glm53.dto.DbTableVO;
import com.jqgg.glm53.dto.TableColumnForm;
import com.jqgg.glm53.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多数据库维护服务测试（H2）：
 * 数据库列表、按库读取表结构、跨库字段检索命中所属库、任意表新增字段。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jqggmulti;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.rag.enabled=false",
        "app.db-maintenance.username=",
})
class MultiDatabaseMaintenanceServiceTest {

    @Autowired
    private MultiDatabaseMaintenanceService service;

    @Autowired
    @Qualifier("dataSource")
    private DataSource dataSource;

    @Test
    void 数据库列表至少包含当前库() {
        var list = service.databases();
        assertThat(list.defaultDatabase()).isNotBlank();
        assertThat(list.databases()).extracting(DbDatabaseVO::name)
                .contains(list.defaultDatabase());
    }

    @Test
    void 按库读取表结构并检索字段所属库() {
        String database = service.databases().defaultDatabase();
        var tables = service.tables(database, null);
        assertThat(tables).extracting(DbTableVO::name)
                .anyMatch("billboard"::equalsIgnoreCase);

        var result = service.search(database, "price_note", false);
        assertThat(result.columnHits()).anySatisfy(hit -> {
            assertThat(hit.database()).isEqualTo(database);
            assertThat(hit.table()).isEqualToIgnoringCase("billboard");
            assertThat(hit.column()).isEqualToIgnoringCase("price_note");
        });
    }

    @Test
    void 任意表可新增字段并立即出现在表结构() throws Exception {
        String database = service.databases().defaultDatabase();
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS db_maint_test");
            st.execute("CREATE TABLE db_maint_test (id BIGINT PRIMARY KEY)");
        }

        TableColumnForm form = new TableColumnForm();
        form.setFieldName("ext_note");
        form.setType("VARCHAR");
        form.setLength(100);
        form.setComment("数据库维护测试字段");
        form.setNullable(true);
        service.addColumn(database, "db_maint_test", form);

        var table = service.tables(database, "db_maint_test").stream()
                .filter(t -> t.name().equalsIgnoreCase("db_maint_test"))
                .findFirst()
                .orElseThrow();
        assertThat(table.columns()).extracting(c -> c.name())
                .anyMatch("ext_note"::equalsIgnoreCase);

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getColumns(conn.getCatalog(), conn.getSchema(),
                    "db_maint_test", "ext_note")) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    @Test
    void 项目广告牌表仍应走专用字段子模块() {
        String database = service.databases().defaultDatabase();
        TableColumnForm form = new TableColumnForm();
        form.setFieldName("ext_should_not_work");
        form.setType("VARCHAR");
        form.setLength(100);
        form.setNullable(true);

        assertThatThrownBy(() -> service.addColumn(database, "billboard", form))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("广告牌字段");
    }
}
