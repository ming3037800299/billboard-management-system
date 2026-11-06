package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.BillboardColumnVO;
import com.jqgg.glm53.dto.BillboardFieldForm;
import com.jqgg.glm53.dto.BillboardFieldVO;
import com.jqgg.glm53.dto.MaintenanceModuleForm;
import com.jqgg.glm53.dto.MaintenanceModuleVO;
import com.jqgg.glm53.entity.Billboard;
import com.jqgg.glm53.entity.BillboardStatus;
import com.jqgg.glm53.entity.MaintenanceModule;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.repository.MaintenanceModuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 广告牌扩展字段服务测试（H2 内存库）：
 * 新增字段的 ALTER TABLE 同步、动态值读写、必填/选项校验、删除列、子模块管理。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jqggfield;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.rag.enabled=false",
})
class BillboardFieldServiceTest {

    @Autowired
    private BillboardFieldService fieldService;

    @Autowired
    private BillboardService billboardService;

    @Autowired
    private DatabaseMaintenanceService databaseService;

    @Autowired
    private MaintenanceModuleRepository moduleRepository;

    private MaintenanceModule builtinFieldModule() {
        return moduleRepository.findByCode("billboard_fields")
                .orElseThrow(() -> new IllegalStateException("内置字段子模块未初始化"));
    }

    private BillboardFieldForm textField(String name, String label, boolean required) {
        BillboardFieldForm form = new BillboardFieldForm();
        form.setFieldName(name);
        form.setLabel(label);
        form.setInputType("TEXT");
        form.setLength(100);
        form.setRequired(required);
        form.setVisible(true);
        form.setSortOrder(10);
        return form;
    }

    private Billboard sample(String code) {
        Billboard b = new Billboard();
        b.setName("扩展字段测试广告牌");
        b.setCode(code);
        b.setRoad("京沪高速G2");
        b.setSection("测试段 K1+000");
        b.setPrice(new BigDecimal("20000"));
        b.setPriceUnit("元/月");
        b.setStatus(BillboardStatus.AVAILABLE);
        return b;
    }

    @Test
    void 新增字段会同步物理列并可保存读取() {
        MaintenanceModule module = builtinFieldModule();
        BillboardFieldVO field = fieldService.addField(module.getId(), textField("ext_test_note", "测试备注", false));

        try {
            assertThat(field.synced()).isTrue();
            assertThat(databaseService.columnExists("billboard", "ext_test_note")).isTrue();

            Billboard saved = billboardService.create(sample("EXT-001"), Map.of("ext_test_note", "一面双立柱"));
            assertThat(fieldService.readValues(saved.getId()))
                    .containsEntry("ext_test_note", "一面双立柱");
            assertThat(fieldService.publicFields())
                    .extracting(p -> p.fieldName())
                    .contains("ext_test_note");
        } finally {
            fieldService.deleteField(field.id());
        }

        assertThat(databaseService.columnExists("billboard", "ext_test_note")).isFalse();
    }

    @Test
    void 必填字段缺失时拒绝保存广告牌() {
        MaintenanceModule module = builtinFieldModule();
        BillboardFieldVO field = fieldService.addField(module.getId(),
                textField("ext_required_note", "必填备注", true));

        try {
            assertThatThrownBy(() -> billboardService.create(sample("EXT-002"), Map.of()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("必填备注");
        } finally {
            fieldService.deleteField(field.id());
        }
    }

    @Test
    void 下拉字段必须使用预设选项() {
        MaintenanceModule module = builtinFieldModule();
        BillboardFieldForm form = textField("ext_lighting", "照明方式", false);
        form.setInputType("SELECT");
        form.setLength(50);
        form.setOptions(List.of("LED", "灯箱"));
        fieldService.addField(module.getId(), form);

        assertThatThrownBy(() -> billboardService.create(sample("EXT-003"),
                Map.of("ext_lighting", "霓虹灯")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("预设选项");

        Billboard saved = billboardService.create(sample("EXT-004"), Map.of("ext_lighting", "LED"));
        assertThat(fieldService.readValues(saved.getId())).containsEntry("ext_lighting", "LED");
    }

    @Test
    void 重复字段名会被拒绝() {
        MaintenanceModule module = builtinFieldModule();
        fieldService.addField(module.getId(), textField("ext_duplicate", "重复字段", false));

        assertThatThrownBy(() -> fieldService.addField(module.getId(), textField("ext_duplicate", "重复字段2", false)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("字段名已存在");
    }

    @Test
    void 数字布尔与日期类型可正常读写() {
        MaintenanceModule module = builtinFieldModule();
        BillboardFieldForm boolForm = textField("ext_test_enabled", "是否亮化", false);
        boolForm.setInputType("BOOLEAN");
        boolForm.setLength(null);
        BillboardFieldVO boolField = fieldService.addField(module.getId(), boolForm);

        BillboardFieldForm decimalForm = textField("ext_test_rate", "亮化费率", false);
        decimalForm.setInputType("DECIMAL");
        decimalForm.setLength(10);
        decimalForm.setDecimals(2);
        BillboardFieldVO decimalField = fieldService.addField(module.getId(), decimalForm);

        BillboardFieldForm dateForm = textField("ext_test_date", "验收日期", false);
        dateForm.setInputType("DATE");
        dateForm.setLength(null);
        BillboardFieldVO dateField = fieldService.addField(module.getId(), dateForm);

        try {
            Billboard saved = billboardService.create(sample("EXT-005"), Map.of(
                    "ext_test_enabled", "true",
                    "ext_test_rate", "3.5",
                    "ext_test_date", "2026-10-19"));

            Map<String, Object> values = fieldService.readValues(saved.getId());
            assertThat(values.get("ext_test_enabled")).isEqualTo(true);
            assertThat((BigDecimal) values.get("ext_test_rate")).isEqualByComparingTo("3.5");
            assertThat(values.get("ext_test_date")).isEqualTo("2026-10-19");
        } finally {
            fieldService.deleteField(boolField.id());
            fieldService.deleteField(decimalField.id());
            fieldService.deleteField(dateField.id());
        }
    }

    @Test
    void 修改显示名称会同步数据库列注释() {
        MaintenanceModule module = builtinFieldModule();
        BillboardFieldVO field = fieldService.addField(module.getId(), textField("ext_label_note", "旧名称", false));
        try {
            BillboardFieldForm update = textField("ext_label_note", "新名称", false);
            fieldService.updateField(field.id(), update);

            var billboard = databaseService.tables("billboard").stream()
                    .filter(t -> t.name().equalsIgnoreCase("billboard"))
                    .findFirst()
                    .orElseThrow();
            assertThat(billboard.columns())
                    .filteredOn(c -> c.name().equalsIgnoreCase("ext_label_note"))
                    .singleElement()
                    .satisfies(c -> assertThat(c.comment()).isEqualTo("新名称"));
        } finally {
            fieldService.deleteField(field.id());
        }
    }

    @Test
    void 数据库字段列表包含系统字段与扩展字段() {
        MaintenanceModule module = builtinFieldModule();
        BillboardFieldVO field = fieldService.addField(module.getId(),
                textField("ext_columns_test", "扩展字段测试", false));
        try {
            List<BillboardColumnVO> columns = fieldService.billboardColumns();
            assertThat(columns).extracting(BillboardColumnVO::name)
                    .contains("id", "name", "code", "price_note", "ext_columns_test");

            BillboardColumnVO system = columns.stream()
                    .filter(c -> c.name().equals("name"))
                    .findFirst()
                    .orElseThrow();
            assertThat(system.origin()).isEqualTo("SYSTEM");
            assertThat(system.label()).isEqualTo("广告牌名称");

            BillboardColumnVO custom = columns.stream()
                    .filter(c -> c.name().equals("ext_columns_test"))
                    .findFirst()
                    .orElseThrow();
            assertThat(custom.origin()).isEqualTo("CUSTOM");
            assertThat(custom.customFieldId()).isEqualTo(field.id());
            assertThat(custom.moduleId()).isEqualTo(module.getId());
            assertThat(custom.synced()).isTrue();
        } finally {
            fieldService.deleteField(field.id());
        }
    }

    @Test
    void 修改系统字段注释会同步数据库() {
        BillboardColumnVO updated = fieldService.updateBillboardColumnComment("name", "测试数据库名称注释");
        assertThat(updated.origin()).isEqualTo("SYSTEM");
        assertThat(updated.comment()).isEqualTo("测试数据库名称注释");

        assertThat(fieldService.billboardColumns())
                .filteredOn(c -> c.name().equals("name"))
                .singleElement()
                .satisfies(c -> assertThat(c.comment()).isEqualTo("测试数据库名称注释"));
    }

    @Test
    void 字段可设置数据库可空性() {
        MaintenanceModule module = builtinFieldModule();
        BillboardFieldForm form = textField("ext_not_null", "非空字段", false);
        form.setNullable(false);
        form.setDefaultValue("x");
        BillboardFieldVO field = fieldService.addField(module.getId(), form);
        try {
            assertThat(field.nullable()).isFalse();
            var column = fieldService.billboardColumns().stream()
                    .filter(c -> c.name().equals("ext_not_null"))
                    .findFirst()
                    .orElseThrow();
            assertThat(column.nullable()).isFalse();
        } finally {
            fieldService.deleteField(field.id());
        }
    }

    @Test
    void 非空字段无默认值时仍可插入广告牌主记录() {
        MaintenanceModule module = builtinFieldModule();
        BillboardFieldForm form = textField("ext_not_null_no_default", "非空无默认字段", false);
        form.setNullable(false);
        BillboardFieldVO field = fieldService.addField(module.getId(), form);
        try {
            assertThat(field.nullable()).isFalse();
            assertThatThrownBy(() -> billboardService.create(sample("NOTNULL-001"), Map.of()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("请填写");

            Billboard saved = billboardService.create(sample("NOTNULL-002"),
                    Map.of("ext_not_null_no_default", "hello"));
            assertThat(fieldService.readValues(saved.getId()))
                    .containsEntry("ext_not_null_no_default", "hello");
        } finally {
            fieldService.deleteField(field.id());
        }
    }

    @Test
    void 可创建多个子模块但内置模块不可删除() {
        MaintenanceModuleForm form = new MaintenanceModuleForm();
        form.setName("运营属性");
        form.setCode("operation_attrs");
        form.setType("BILLBOARD_ATTRIBUTE");
        form.setDescription("测试自定义子模块");
        MaintenanceModuleVO created = fieldService.createModule(form);

        assertThat(created.id()).isNotNull();
        assertThat(created.builtin()).isFalse();
        assertThat(fieldService.listModules())
                .extracting(MaintenanceModuleVO::code)
                .contains("operation_attrs");

        fieldService.deleteModule(created.id());
        assertThat(fieldService.listModules())
                .extracting(MaintenanceModuleVO::code)
                .doesNotContain("operation_attrs");

        MaintenanceModule builtin = builtinFieldModule();
        assertThatThrownBy(() -> fieldService.deleteModule(builtin.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("内置子模块不可删除");
    }
}
