package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.BillboardColumnVO;
import com.jqgg.glm53.dto.BillboardFieldForm;
import com.jqgg.glm53.dto.BillboardFieldGroupVO;
import com.jqgg.glm53.dto.BillboardFieldVO;
import com.jqgg.glm53.dto.DbColumnVO;
import com.jqgg.glm53.dto.DbTableVO;
import com.jqgg.glm53.dto.MaintenanceModuleForm;
import com.jqgg.glm53.dto.MaintenanceModuleVO;
import com.jqgg.glm53.dto.PublicBillboardFieldVO;
import com.jqgg.glm53.entity.BillboardField;
import com.jqgg.glm53.entity.FieldInputType;
import com.jqgg.glm53.entity.MaintenanceModule;
import com.jqgg.glm53.entity.MaintenanceModuleType;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.exception.NotFoundException;
import com.jqgg.glm53.repository.BillboardFieldRepository;
import com.jqgg.glm53.repository.MaintenanceModuleRepository;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 广告牌扩展字段 / 属性服务：
 *   1. 维护数据库维护子模块及其下的字段定义；
 *   2. 新增字段时执行 ALTER TABLE billboard ADD COLUMN，物理列与元数据同步；
 *   3. 为广告牌新增 / 编辑提供动态值校验与读写；
 *   4. 为前台详情与知识库提供扩展字段展示值。
 *
 * 说明：数据库列统一允许 NULL（必填由应用层校验），避免存量广告牌导致加列失败；
 * 多行文本列（TEXT）不支持数据库默认值，与 MySQL 限制保持一致。
 */
@Service
public class BillboardFieldService {

    private static final Logger log = LoggerFactory.getLogger(BillboardFieldService.class);

    /** 扩展字段所在的物理表 */
    public static final String BILLBOARD_TABLE = "billboard";

    /** billboard 核心列，禁止作为扩展字段名 */
    private static final Set<String> RESERVED_COLUMNS = Set.of(
            "id", "name", "code", "road", "section", "address", "longitude", "latitude",
            "images", "image_meta", "price", "price_unit", "price_note", "size", "material",
            "visible_area", "audience", "status", "description", "deleted",
            "created_at", "updated_at");

    /** billboard 系统字段的默认显示名称（数据库注释缺失时兜底）。 */
    private static final Map<String, String> SYSTEM_LABELS = Map.ofEntries(
            Map.entry("id", "主键ID"),
            Map.entry("name", "广告牌名称"),
            Map.entry("code", "广告牌编号"),
            Map.entry("road", "所在公路"),
            Map.entry("section", "所属路段"),
            Map.entry("address", "详细位置"),
            Map.entry("longitude", "经度"),
            Map.entry("latitude", "纬度"),
            Map.entry("images", "实拍图片"),
            Map.entry("image_meta", "图片分类信息"),
            Map.entry("price", "租赁单价"),
            Map.entry("price_unit", "计费周期"),
            Map.entry("price_note", "报价说明"),
            Map.entry("size", "尺寸规格"),
            Map.entry("material", "画面材质"),
            Map.entry("visible_area", "可视面积"),
            Map.entry("audience", "主要受众"),
            Map.entry("status", "租赁状态"),
            Map.entry("description", "详细简介"),
            Map.entry("deleted", "删除标记"),
            Map.entry("created_at", "创建时间"),
            Map.entry("updated_at", "更新时间"));

    private static final int MAX_TEXT_LENGTH = 2000;
    private static final int MAX_TEXTAREA_LENGTH = 20_000;
    private static final int MAX_OPTION_LENGTH = 200;
    private static final int MAX_SELECT_LENGTH = 500;

    /** 表单分区 */
    private static final Set<String> FORM_SECTIONS = Set.of(
            "basic", "location", "price", "params", "extension");

    /** 表单栏宽 */
    private static final Set<String> FORM_WIDTHS = Set.of("half", "full");

    /** 内置子模块（首次启动自动补齐） */
    private static final List<BuiltinModule> BUILTINS = List.of(
            new BuiltinModule("db_search", "数据库检索", MaintenanceModuleType.SEARCH,
                    "按表名、字段名、中文注释或表内数据内容，查询数据所在的数据库表与字段。", 10),
            new BuiltinModule("schema_browser", "数据表结构", MaintenanceModuleType.SCHEMA,
                    "浏览当前数据库全部表、字段类型、注释、主键与数据量。", 20),
            new BuiltinModule("billboard_fields", "广告牌字段", MaintenanceModuleType.BILLBOARD_FIELD,
                    "查看 billboard 表已有字段并维护扩展字段；新增字段同步 ALTER TABLE，编辑注释同步数据库。", 30),
            new BuiltinModule("billboard_attributes", "广告牌属性", MaintenanceModuleType.BILLBOARD_ATTRIBUTE,
                    "查看广告牌已有属性并维护扩展属性（下拉、日期、开关等）；新增与注释修改同步数据库。", 40),
            new BuiltinModule("field_manager", "字段管理", MaintenanceModuleType.FIELD_MANAGER,
                    "统一管理字段启用/停用、表单分区、栏宽、排序，支持新增和删除扩展字段。", 50),
            new BuiltinModule("form_designer", "表单设计", MaintenanceModuleType.FORM_DESIGNER,
                    "按表单分区放置扩展字段，调整栏宽和顺序，并预览表单效果。", 60));

    private final MaintenanceModuleRepository moduleRepository;
    private final BillboardFieldRepository fieldRepository;
    private final DatabaseMaintenanceService databaseService;
    private final JdbcTemplate jdbcTemplate;

    public BillboardFieldService(MaintenanceModuleRepository moduleRepository,
                                 BillboardFieldRepository fieldRepository,
                                 DatabaseMaintenanceService databaseService,
                                 @Qualifier("dataSource") DataSource dataSource) {
        this.moduleRepository = moduleRepository;
        this.fieldRepository = fieldRepository;
        this.databaseService = databaseService;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(5);
    }

    // ==================== 子模块 ====================

    /** 首次启动补齐内置子模块；数据库维护表不存在时仅记录警告，不影响其他功能。 */
    public void ensureBuiltinModules() {
        try {
            for (BuiltinModule builtin : BUILTINS) {
                if (moduleRepository.existsByCode(builtin.code())) {
                    continue;
                }
                MaintenanceModule module = new MaintenanceModule();
                module.setName(builtin.name());
                module.setCode(builtin.code());
                module.setType(builtin.type());
                module.setDescription(builtin.description());
                module.setSortOrder(builtin.sortOrder());
                module.setEnabled(true);
                module.setBuiltin(true);
                moduleRepository.save(module);
            }
        } catch (Exception e) {
            log.warn("数据库维护表尚未初始化，请执行 sql/db-maintenance.sql：{}", e.getMessage());
        }
    }

    /** 全部子模块（含字段数量）。 */
    public List<MaintenanceModuleVO> listModules() {
        Map<Long, Long> counts = fieldRepository.findAll().stream()
                .collect(Collectors.groupingBy(BillboardField::getModuleId, Collectors.counting()));
        return moduleRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(m -> toModuleVO(m, counts.getOrDefault(m.getId(), 0L)))
                .toList();
    }

    /** 新建子模块；内置模块的 code / type 固定不可新建重复项。 */
    @Transactional
    public MaintenanceModuleVO createModule(MaintenanceModuleForm form) {
        String code = normalizeCode(form.getCode());
        if (moduleRepository.existsByCode(code)) {
            throw new BadRequestException("模块标识已存在：" + code);
        }
        MaintenanceModuleType type = MaintenanceModuleType.parse(form.getType());
        if (type == null) {
            throw new BadRequestException("请选择合法的子模块类型");
        }
        MaintenanceModule module = new MaintenanceModule();
        module.setName(form.getName().trim());
        module.setCode(code);
        module.setType(type);
        module.setDescription(trimToNull(form.getDescription()));
        module.setSortOrder(form.getSortOrder() == null ? nextModuleSort() : form.getSortOrder());
        module.setEnabled(form.getEnabled() == null || form.getEnabled());
        module.setBuiltin(false);
        return toModuleVO(moduleRepository.save(module), 0);
    }

    /** 编辑子模块：名称、说明、排序、启用状态；标识与类型不可修改。 */
    @Transactional
    public MaintenanceModuleVO updateModule(Long id, MaintenanceModuleForm form) {
        MaintenanceModule module = requireModule(id);
        module.setName(form.getName().trim());
        module.setDescription(trimToNull(form.getDescription()));
        if (form.getSortOrder() != null) {
            module.setSortOrder(form.getSortOrder());
        }
        if (form.getEnabled() != null) {
            module.setEnabled(form.getEnabled());
        }
        return toModuleVO(moduleRepository.save(module), fieldRepository.countByModuleId(id));
    }

    /** 删除自定义子模块；内置模块或仍有字段的模块不可删除。 */
    @Transactional
    public void deleteModule(Long id) {
        MaintenanceModule module = requireModule(id);
        if (module.isBuiltin()) {
            throw new BadRequestException("内置子模块不可删除，可修改名称或停用");
        }
        if (fieldRepository.existsByModuleId(id)) {
            throw new BadRequestException("该子模块下仍有字段/属性，请先删除字段后再删除子模块");
        }
        moduleRepository.delete(module);
    }

    // ==================== 字段 ====================

    /** 某子模块下的字段列表。 */
    public List<BillboardFieldVO> listFields(Long moduleId) {
        MaintenanceModule module = requireModule(moduleId);
        Set<String> existing = databaseService.existingColumns(BILLBOARD_TABLE);
        return fieldRepository.findByModuleIdOrderBySortOrderAscIdAsc(moduleId).stream()
                .map(f -> toFieldVO(f, module, existing.contains(f.getFieldName().toLowerCase(Locale.ROOT))))
                .toList();
    }

    /**
     * billboard 表的全部实际字段（数据库维护页展示）：
     *   - 系统字段直接来自当前数据库表结构；
     *   - 扩展字段与 billboard_field 元数据合并，物理列缺失的显示为“待同步”。
     */
    public List<BillboardColumnVO> billboardColumns() {
        DbTableVO table = databaseService.tables("billboard").stream()
                .filter(t -> t.name().equalsIgnoreCase(BILLBOARD_TABLE))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("billboard 表不存在，请先执行 sql/init.sql"));
        List<BillboardField> customFields = allFields();
        Map<String, BillboardField> byName = customFields.stream()
                .collect(Collectors.toMap(f -> f.getFieldName().toLowerCase(Locale.ROOT), f -> f, (a, b) -> a));
        Map<Long, MaintenanceModule> modules = moduleRepository.findAll().stream()
                .collect(Collectors.toMap(MaintenanceModule::getId, m -> m, (a, b) -> a));

        Set<String> seen = new LinkedHashSet<>();
        List<BillboardColumnVO> result = new ArrayList<>();
        for (DbColumnVO column : table.columns()) {
            String key = column.name().toLowerCase(Locale.ROOT);
            seen.add(key);
            BillboardField field = byName.get(key);
            if (field != null) {
                result.add(toColumnVO(column, field, modules.get(field.getModuleId()), true));
            } else {
                String label = SYSTEM_LABELS.getOrDefault(key,
                        column.comment() == null || column.comment().isBlank() ? column.name() : column.comment());
                result.add(new BillboardColumnVO(
                        column.name(),
                        column.type(),
                        column.comment(),
                        column.nullable(),
                        column.defaultValue(),
                        column.key(),
                        column.extra(),
                        column.primaryKey(),
                        "SYSTEM",
                        null,
                        null,
                        "系统字段",
                        null,
                        label,
                        null,
                        null,
                        null,
                        null,
                        !column.nullable(),
                        false,
                        List.of(),
                        null,
                        0,
                        true,
                        systemSection(column.name()),
                        "half",
                        true));
            }
        }
        // 只登记了元数据、物理列尚未同步的扩展字段
        customFields.stream()
                .filter(f -> !seen.contains(f.getFieldName().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingInt(BillboardField::getSortOrder))
                .forEach(f -> result.add(toColumnVO(null, f, modules.get(f.getModuleId()), false)));
        return result;
    }

    /**
     * 修改数据库字段注释：
     *   - 系统字段：只更新数据库列注释；
     *   - 扩展字段：同时更新扩展字段显示名称，保持表单元数据与数据库一致。
     */
    public BillboardColumnVO updateBillboardColumnComment(String name, String comment) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("请指定要修改的数据库字段");
        }
        String column = name.trim().toLowerCase(Locale.ROOT);
        if (!databaseService.columnExists(BILLBOARD_TABLE, column)) {
            throw new BadRequestException("数据库字段不存在：" + name);
        }
        String value = comment == null ? "" : comment.trim();
        Optional<BillboardField> custom = fieldRepository.findByFieldName(column);
        if (custom.isPresent()) {
            if (value.isEmpty()) {
                throw new BadRequestException("扩展字段显示名称不能为空");
            }
            if (value.length() > 100) {
                throw new BadRequestException("扩展字段显示名称不能超过 100 字");
            }
        } else if (value.length() > 500) {
            throw new BadRequestException("字段注释不能超过 500 字");
        }

        databaseService.updateColumnComment(BILLBOARD_TABLE, column, value);
        custom.ifPresent(field -> {
            field.setLabel(value);
            fieldRepository.save(field);
        });
        return billboardColumns().stream()
                .filter(c -> c.name().equalsIgnoreCase(column))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("字段不存在：" + name));
    }

    /** 后台广告牌表单：按启用的字段/属性子模块分组返回全部字段定义。 */
    public List<BillboardFieldGroupVO> listFieldGroups() {
        Set<String> existing = databaseService.existingColumns(BILLBOARD_TABLE);
        List<BillboardFieldGroupVO> groups = new ArrayList<>();
        for (MaintenanceModule module : enabledFieldModules()) {
            List<BillboardFieldVO> fields = fieldRepository
                    .findByModuleIdOrderBySortOrderAscIdAsc(module.getId()).stream()
                    // 数据库列缺失（待同步）的字段不进入广告牌表单，避免保存时写入不存在的列
                    .filter(f -> f.isEnabled() && existing.contains(f.getFieldName().toLowerCase(Locale.ROOT)))
                    .map(f -> toFieldVO(f, module, true))
                    .toList();
            if (fields.isEmpty()) {
                continue;
            }
            groups.add(new BillboardFieldGroupVO(
                    module.getId(),
                    module.getName(),
                    module.getCode(),
                    module.getType().name(),
                    module.getType().getLabel(),
                    module.getDescription(),
                    module.getSortOrder(),
                    fields));
        }
        return groups;
    }

    /** 前台公开字段元数据：仅启用模块下允许前台展示且已同步的字段。 */
    public List<PublicBillboardFieldVO> publicFields() {
        List<PublicBillboardFieldVO> result = new ArrayList<>();
        Set<String> existing = databaseService.existingColumns(BILLBOARD_TABLE);
        for (MaintenanceModule module : enabledFieldModules()) {
            for (BillboardField field : fieldRepository.findByModuleIdOrderBySortOrderAscIdAsc(module.getId())) {
                if (!field.isEnabled()
                        || !field.isVisible()
                        || !existing.contains(field.getFieldName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                result.add(new PublicBillboardFieldVO(
                        field.getFieldName(),
                        field.getLabel(),
                        field.getInputType().name(),
                        field.isRequired(),
                        BillboardFieldVO.parseOptions(field.getOptionsText()),
                        field.getPlaceholder(),
                        field.getSortOrder(),
                        module.getName(),
                        module.getType().name()));
            }
        }
        result.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        return result;
    }

    /** 新增字段：先登记元数据，再执行 ALTER TABLE；DDL 失败时保留元数据以便“同步”重试。 */
    public BillboardFieldVO addField(Long moduleId, BillboardFieldForm form) {
        MaintenanceModule module = requireModule(moduleId);
        if (!module.getType().isFieldModule()) {
            throw new BadRequestException("当前子模块不支持字段维护，请选择“广告牌字段”或“广告牌属性”类型");
        }
        String fieldName = normalizeFieldName(form.getFieldName());
        if (fieldRepository.existsByFieldName(fieldName)) {
            throw new BadRequestException("字段名已存在：" + fieldName);
        }
        Set<String> existing = databaseService.existingColumns(BILLBOARD_TABLE);
        if (existing.contains(fieldName)) {
            throw new BadRequestException("数据库中已存在列 " + fieldName + "，请更换字段名");
        }

        FieldInputType inputType = FieldInputType.parse(form.getInputType());
        if (inputType == null) {
            throw new BadRequestException("请选择合法的字段类型");
        }
        String label = form.getLabel().trim();
        Integer length = resolveLength(inputType, form.getLength());
        Integer decimals = resolveDecimals(inputType, form.getDecimals(), length);
        List<String> options = normalizeOptions(form.getOptions());
        validateOptions(inputType, options);
        String defaultValue = trimToNull(form.getDefaultValue());
        String defaultLiteral = buildDefaultLiteral(inputType, defaultValue, options, length);
        boolean nullable = form.getNullable() == null || form.getNullable();

        BillboardField field = new BillboardField();
        field.setModuleId(moduleId);
        field.setFieldName(fieldName);
        field.setLabel(label);
        field.setInputType(inputType);
        field.setColumnType(buildColumnType(inputType, length, decimals));
        field.setLength(length);
        field.setDecimals(decimals);
        field.setRequired(Boolean.TRUE.equals(form.getRequired()));
        field.setNullable(nullable);
        field.setDefaultValue(defaultValue);
        field.setOptionsText(BillboardFieldVO.writeOptions(options));
        field.setPlaceholder(trimToNull(form.getPlaceholder()));
        field.setSortOrder(form.getSortOrder() == null ? nextFieldSort(moduleId) : form.getSortOrder());
        field.setVisible(form.getVisible() == null || form.getVisible());
        field.setEnabled(form.getEnabled() == null || form.getEnabled());
        field.setSection(normalizeSection(form.getSection()));
        field.setWidth(normalizeWidth(form.getWidth()));

        // 先落元数据：即使 DDL 失败，页面上也能看到“待同步”的字段并重试
        BillboardField saved = fieldRepository.save(field);
        addPhysicalColumn(saved, defaultLiteral);
        return toFieldVO(saved, module, true);
    }

    /**
     * 编辑字段：支持修改显示属性，也支持重命名物理列 / 修改字段类型。
     * 重命名或类型变更通过 MySQL CHANGE COLUMN 完成，已有数据由数据库自动转换。
     */
    @Transactional
    public BillboardFieldVO updateField(Long id, BillboardFieldForm form) {
        BillboardField field = requireField(id);
        MaintenanceModule module = requireModule(field.getModuleId());

        String oldName = field.getFieldName();
        String newName = form.getFieldName() == null || form.getFieldName().isBlank()
                ? oldName : normalizeFieldName(form.getFieldName());
        FieldInputType newType = FieldInputType.parse(form.getInputType());
        if (newType == null) {
            newType = field.getInputType();
        }
        if (newType == null) {
            throw new BadRequestException("请选择合法的字段类型");
        }
        Integer newLength = resolveLength(newType,
                form.getLength() != null ? form.getLength() : field.getLength());
        Integer newDecimals = resolveDecimals(newType,
                form.getDecimals() != null ? form.getDecimals() : field.getDecimals(), newLength);
        String newColumnType = buildColumnType(newType, newLength, newDecimals);
        List<String> options = normalizeOptions(form.getOptions());
        validateOptions(newType, options);
        String defaultValue = trimToNull(form.getDefaultValue());
        String defaultLiteral = buildDefaultLiteral(newType, defaultValue, options, newLength);
        String label = form.getLabel().trim();
        boolean newNullable = form.getNullable() != null ? form.getNullable() : field.isNullable();

        boolean renamed = !newName.equals(oldName);
        boolean retyped = newType != field.getInputType()
                || !Objects.equals(newLength, field.getLength())
                || !Objects.equals(newDecimals, field.getDecimals());
        boolean nullableChanged = newNullable != field.isNullable();
        boolean structural = renamed || retyped || nullableChanged;
        boolean synced = databaseService.columnExists(BILLBOARD_TABLE, oldName);

        if (renamed) {
            fieldRepository.findByFieldName(newName)
                    .filter(other -> !other.getId().equals(field.getId()))
                    .ifPresent(other -> {
                        throw new BadRequestException("字段名已存在：" + newName);
                    });
            if (databaseService.existingColumns(BILLBOARD_TABLE).contains(newName)) {
                throw new BadRequestException("数据库中已存在列 " + newName + "，请更换字段名");
            }
        }

        if (structural && synced) {
            if (!databaseService.isMySql()) {
                throw new BadRequestException("当前数据库暂不支持在线重命名字段、修改字段类型或可空性，请使用 MySQL 8");
            }
            String ddlDefault = defaultLiteral;
            if (!newNullable && ddlDefault == null) {
                ddlDefault = implicitNotNullDefaultLiteral(newType);
            }
            if (!newNullable) {
                // 改为非空前列必须先回填 NULL，否则 MODIFY NOT NULL 会失败
                databaseService.fillNulls(BILLBOARD_TABLE, oldName, ddlDefault);
            }
            databaseService.changeColumn(BILLBOARD_TABLE, oldName, newName, newColumnType, label,
                    ddlDefault, newNullable);
        } else if (synced && !structural) {
            // 只修改显示属性：同步数据库列注释
            databaseService.updateColumnComment(BILLBOARD_TABLE, newName, label);
        }

        field.setFieldName(newName);
        field.setInputType(newType);
        field.setColumnType(newColumnType);
        field.setLength(newLength);
        field.setDecimals(newDecimals);
        field.setLabel(label);
        field.setRequired(Boolean.TRUE.equals(form.getRequired()));
        field.setNullable(newNullable);
        field.setDefaultValue(defaultValue);
        field.setOptionsText(BillboardFieldVO.writeOptions(options));
        field.setPlaceholder(trimToNull(form.getPlaceholder()));
        if (form.getSortOrder() != null) {
            field.setSortOrder(form.getSortOrder());
        }
        if (form.getVisible() != null) {
            field.setVisible(form.getVisible());
        }
        if (form.getEnabled() != null) {
            field.setEnabled(form.getEnabled());
        }
        if (form.getSection() != null) {
            field.setSection(normalizeSection(form.getSection()));
        }
        if (form.getWidth() != null) {
            field.setWidth(normalizeWidth(form.getWidth()));
        }
        BillboardField saved = fieldRepository.save(field);
        boolean nowSynced = databaseService.columnExists(BILLBOARD_TABLE, saved.getFieldName());
        return toFieldVO(saved, module, nowSynced);
    }

    /** 更新字段启用状态 / 表单分区 / 栏宽 / 排序等布局配置（不触发表结构变更）。 */
    @Transactional
    public BillboardFieldVO updateLayout(Long id, com.jqgg.glm53.dto.FieldLayoutForm form) {
        BillboardField field = requireField(id);
        MaintenanceModule module = requireModule(field.getModuleId());
        if (form.getEnabled() != null) {
            field.setEnabled(form.getEnabled());
        }
        if (form.getSection() != null) {
            field.setSection(normalizeSection(form.getSection()));
        }
        if (form.getWidth() != null) {
            field.setWidth(normalizeWidth(form.getWidth()));
        }
        if (form.getSortOrder() != null) {
            field.setSortOrder(form.getSortOrder());
        }
        if (form.getRequired() != null) {
            field.setRequired(form.getRequired());
        }
        if (form.getVisible() != null) {
            field.setVisible(form.getVisible());
        }
        BillboardField saved = fieldRepository.save(field);
        boolean synced = databaseService.columnExists(BILLBOARD_TABLE, saved.getFieldName());
        return toFieldVO(saved, module, synced);
    }

    /** 删除字段：先停用元数据并执行 DROP COLUMN，成功后再删除元数据；DDL 失败时恢复原启用状态。 */
    @Transactional
    public void deleteField(Long id) {
        BillboardField field = requireField(id);
        boolean wasEnabled = field.isEnabled();
        // 先标记停用，避免 DDL 执行期间后台表单继续提交该字段
        field.setEnabled(false);
        fieldRepository.save(field);
        try {
            databaseService.dropColumn(BILLBOARD_TABLE, field.getFieldName());
        } catch (RuntimeException e) {
            field.setEnabled(wasEnabled);
            fieldRepository.save(field);
            throw e;
        }
        fieldRepository.delete(field);
    }

    /** 修复同步：数据库列缺失时按元数据重新新增列。 */
    public BillboardFieldVO syncField(Long id) {
        BillboardField field = requireField(id);
        MaintenanceModule module = requireModule(field.getModuleId());
        if (!databaseService.columnExists(BILLBOARD_TABLE, field.getFieldName())) {
            String literal = buildDefaultLiteral(
                    field.getInputType(),
                    field.getDefaultValue(),
                    BillboardFieldVO.parseOptions(field.getOptionsText()),
                    field.getLength());
            addPhysicalColumn(field, literal);
        }
        return toFieldVO(field, module, true);
    }

    // ==================== 动态值读写 ====================

    /**
     * 校验并规范化扩展值。
     *
     * @param raw 前端提交的 JSON 对象（字段名 → 值）
     * @return 字段名 → 规范化后的 JDBC 值（包含显式 null，用于清空）
     */
    public Map<String, Object> validateAndNormalize(Map<String, Object> raw) {
        Map<String, Object> input = raw == null ? Map.of() : raw;
        List<BillboardField> fields = enabledFields();
        if (fields.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> lowerKeys = new HashMap<>();
        input.forEach((k, v) -> {
            if (k != null) {
                lowerKeys.put(k.toLowerCase(Locale.ROOT), v);
            }
        });

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (BillboardField field : fields) {
            String key = field.getFieldName().toLowerCase(Locale.ROOT);
            boolean present = lowerKeys.containsKey(key) || input.containsKey(field.getFieldName());
            Object value = lowerKeys.containsKey(key) ? lowerKeys.get(key) : input.get(field.getFieldName());
            if (value instanceof String s && s.isBlank()) {
                value = null;
            }
            if (value == null) {
                if (field.isRequired() || !field.isNullable()) {
                    String fallback = field.getDefaultValue();
                    if (fallback != null && !fallback.isBlank()) {
                        normalized.put(field.getFieldName(), convertValue(field, fallback));
                        continue;
                    }
                    throw new BadRequestException("请填写「" + field.getLabel() + "」");
                }
                if (present) {
                    normalized.put(field.getFieldName(), null);
                }
                continue;
            }
            normalized.put(field.getFieldName(), convertValue(field, value));
        }
        return normalized;
    }

    /** 写入扩展值（与核心字段保存在同一事务内由 BillboardService 调用）。 */
    public void writeValues(Long billboardId, Map<String, Object> normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return;
        }
        Map<String, BillboardField> fields = allFields().stream()
                .collect(Collectors.toMap(f -> f.getFieldName().toLowerCase(Locale.ROOT), f -> f, (a, b) -> a));
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        normalized.forEach((name, value) -> {
            BillboardField field = fields.get(name.toLowerCase(Locale.ROOT));
            if (field != null) {
                columns.add(field.getFieldName());
                values.add(value);
            }
        });
        if (columns.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(databaseService.quote(BILLBOARD_TABLE)).append(" SET ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(databaseService.quote(columns.get(i))).append(" = ?");
        }
        sql.append(" WHERE ").append(databaseService.quote("id")).append(" = ?");
        try {
            jdbcTemplate.update(sql.toString(), (PreparedStatement ps) -> {
                for (int i = 0; i < values.size(); i++) {
                    ps.setObject(i + 1, values.get(i));
                }
                ps.setLong(values.size() + 1, billboardId);
            });
        } catch (Exception e) {
            log.warn("保存广告牌扩展字段失败，id={}：{}", billboardId, e.getMessage());
            throw new BadRequestException("扩展字段保存失败：" + e.getMessage());
        }
    }

    /** 读取单条广告牌扩展值。 */
    public Map<String, Object> readValues(Long billboardId) {
        if (billboardId == null) {
            return Map.of();
        }
        List<BillboardField> fields = enabledFields();
        if (fields.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT " + selectColumns(fields) + " FROM "
                + databaseService.quote(BILLBOARD_TABLE) + " WHERE "
                + databaseService.quote("id") + " = ?";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(sql, (ps) -> ps.setLong(1, billboardId),
                    (rs, rowNum) -> mapRow(rs, fields));
            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (Exception e) {
            log.debug("读取广告牌扩展字段失败，id={}：{}", billboardId, e.getMessage());
            return Map.of();
        }
    }

    /** 批量读取扩展值（后台 / 前台列表用）。 */
    public Map<Long, Map<String, Object>> readValues(List<Long> billboardIds) {
        if (billboardIds == null || billboardIds.isEmpty()) {
            return Map.of();
        }
        List<BillboardField> fields = enabledFields();
        if (fields.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<String, Object>> result = new HashMap<>();
        List<Long> ids = billboardIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            List<Long> chunk = ids.subList(start, Math.min(start + 500, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = "SELECT " + databaseService.quote("id") + ", " + selectColumns(fields)
                    + " FROM " + databaseService.quote(BILLBOARD_TABLE)
                    + " WHERE " + databaseService.quote("id") + " IN (" + placeholders + ")";
            try {
                jdbcTemplate.query(sql, chunk.toArray(), (rs, rowNum) -> {
                    long id = rs.getLong(1);
                    result.put(id, mapRow(rs, fields, 2));
                    return null;
                });
            } catch (Exception e) {
                log.debug("批量读取广告牌扩展字段失败：{}", e.getMessage());
                return result;
            }
        }
        return result;
    }

    /** 知识库用：扩展字段的「标签 → 展示值」列表（跳过空值）。 */
    public List<DisplayValue> displayValues(Long billboardId) {
        Map<String, Object> values = readValues(billboardId);
        if (values.isEmpty()) {
            return List.of();
        }
        Map<String, BillboardField> fieldMap = allFields().stream()
                .collect(Collectors.toMap(f -> f.getFieldName().toLowerCase(Locale.ROOT), f -> f, (a, b) -> a));
        List<DisplayValue> result = new ArrayList<>();
        values.forEach((name, value) -> {
            BillboardField field = fieldMap.get(name.toLowerCase(Locale.ROOT));
            String text = displayText(field, value);
            if (field != null && text != null && !text.isBlank()) {
                result.add(new DisplayValue(field.getLabel(), text, field.getSortOrder()));
            }
        });
        result.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        return result;
    }

    /** 扩展字段展示值。 */
    public record DisplayValue(String label, String value, int sortOrder) {
    }

    // ==================== 内部：转换与校验 ====================

    private Object convertValue(BillboardField field, Object value) {
        String label = field.getLabel();
        try {
            return switch (field.getInputType()) {
                case TEXT, SELECT -> {
                    String text = String.valueOf(value).trim();
                    if (field.getInputType() == FieldInputType.SELECT) {
                        List<String> options = BillboardFieldVO.parseOptions(field.getOptionsText());
                        if (!options.isEmpty() && !options.contains(text)) {
                            throw new BadRequestException("「" + label + "」的值必须是预设选项之一");
                        }
                    }
                    int max = field.getLength() == null ? MAX_TEXT_LENGTH : field.getLength();
                    yield truncateOrThrow(text, max, label);
                }
                case TEXTAREA -> truncateOrThrow(String.valueOf(value), MAX_TEXTAREA_LENGTH, label);
                case INTEGER -> {
                    long number = value instanceof Number n ? n.longValue()
                            : Long.parseLong(String.valueOf(value).trim());
                    if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                        throw new BadRequestException("「" + label + "」超出整数范围");
                    }
                    yield (int) number;
                }
                case DECIMAL -> new BigDecimal(String.valueOf(value).trim());
                case DATE -> LocalDate.parse(String.valueOf(value).trim());
                case DATETIME -> parseDateTime(String.valueOf(value).trim());
                case BOOLEAN -> parseBoolean(value);
            };
        } catch (BadRequestException e) {
            throw e;
        } catch (NumberFormatException e) {
            throw new BadRequestException("「" + label + "」必须是数字");
        } catch (DateTimeParseException e) {
            throw new BadRequestException("「" + label + "」日期格式不正确");
        } catch (Exception e) {
            throw new BadRequestException("「" + label + "」格式不正确：" + e.getMessage());
        }
    }

    private String truncateOrThrow(String value, int max, String label) {
        if (value.length() > max) {
            throw new BadRequestException("「" + label + "」不能超过 " + max + " 个字");
        }
        return value;
    }

    private LocalDateTime parseDateTime(String raw) {
        String value = raw.replace(' ', 'T');
        if (value.length() == 16) {
            value = value + ":00";
        }
        return LocalDateTime.parse(value);
    }

    private Boolean parseBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "1", "是", "yes", "y", "on" -> true;
            case "false", "0", "否", "no", "n", "off" -> false;
            default -> throw new BadRequestException("请填写“是”或“否”");
        };
    }

    private Integer resolveLength(FieldInputType type, Integer requested) {
        if (type == FieldInputType.TEXT || type == FieldInputType.SELECT) {
            int length = requested == null ? (type == FieldInputType.TEXT ? 200 : 100) : requested;
            int max = type == FieldInputType.TEXT ? MAX_TEXT_LENGTH : MAX_SELECT_LENGTH;
            if (length < 1 || length > max) {
                throw new BadRequestException("字段长度需在 1 - " + max + " 之间");
            }
            return length;
        }
        if (type == FieldInputType.DECIMAL) {
            int precision = requested == null ? 18 : requested;
            if (precision < 1 || precision > 65) {
                throw new BadRequestException("小数精度需在 1 - 65 之间");
            }
            return precision;
        }
        return null;
    }

    private Integer resolveDecimals(FieldInputType type, Integer requested, Integer length) {
        if (type != FieldInputType.DECIMAL) {
            return null;
        }
        int precision = length == null ? 18 : length;
        int scale = requested == null ? 2 : requested;
        if (scale < 0 || scale > 30 || scale > precision) {
            throw new BadRequestException("小数位需在 0 - 30 之间且不能大于精度");
        }
        return scale;
    }

    private String buildColumnType(FieldInputType type, Integer length, Integer decimals) {
        return switch (type) {
            case TEXT -> "VARCHAR(" + length + ")";
            case SELECT -> "VARCHAR(" + (length == null ? 100 : length) + ")";
            case TEXTAREA -> "TEXT";
            case INTEGER -> "INT";
            case DECIMAL -> {
                int precision = length == null || length > 65 ? 18 : length;
                yield "DECIMAL(" + precision + "," + (decimals == null ? 2 : decimals) + ")";
            }
            case DATE -> "DATE";
            case DATETIME -> "DATETIME";
            case BOOLEAN -> "TINYINT(1)";
        };
    }

    private String buildDefaultLiteral(FieldInputType type, String defaultValue, List<String> options, Integer length) {
        if (defaultValue == null || defaultValue.isBlank()) {
            return null;
        }
        String value = defaultValue.trim();
        try {
            return switch (type) {
                case TEXT -> {
                    int max = length == null ? MAX_TEXT_LENGTH : length;
                    if (value.length() > max) {
                        throw new BadRequestException("默认值不能超过字段长度 " + max);
                    }
                    yield sqlString(value);
                }
                case TEXTAREA -> throw new BadRequestException("多行文本字段不支持数据库默认值");
                case SELECT -> {
                    if (options.isEmpty() || !options.contains(value)) {
                        throw new BadRequestException("默认值必须是下拉选项之一");
                    }
                    yield sqlString(value);
                }
                case INTEGER -> String.valueOf(Long.parseLong(value));
                case DECIMAL -> new BigDecimal(value).toPlainString();
                case DATE -> sqlString(LocalDate.parse(value).toString());
                case DATETIME -> sqlString(parseDateTime(value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                case BOOLEAN -> parseBoolean(value) ? "1" : "0";
            };
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("默认值格式不正确：" + value);
        }
    }

    private List<String> normalizeOptions(List<String> options) {
        if (options == null) {
            return List.of();
        }
        return options.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .peek(s -> {
                    if (s.length() > MAX_OPTION_LENGTH) {
                        throw new BadRequestException("单个下拉选项不能超过 " + MAX_OPTION_LENGTH + " 字");
                    }
                })
                .distinct()
                .toList();
    }

    private void validateOptions(FieldInputType type, List<String> options) {
        if (type == FieldInputType.SELECT && options.isEmpty()) {
            throw new BadRequestException("下拉选项字段至少需要配置一个选项");
        }
    }

    private String normalizeFieldName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("请填写字段名");
        }
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("^[a-z][a-z0-9_]{1,62}$")) {
            throw new BadRequestException("字段名需为小写字母开头，可包含数字和下划线（2-63 位）");
        }
        if (RESERVED_COLUMNS.contains(name)) {
            throw new BadRequestException("字段名与广告牌核心字段冲突：" + name);
        }
        if (name.startsWith("__")) {
            throw new BadRequestException("字段名不能以双下划线开头");
        }
        return name;
    }

    private String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("请填写模块标识");
        }
        String code = raw.trim().toLowerCase(Locale.ROOT);
        if (!code.matches("^[a-z][a-z0-9_]{1,49}$")) {
            throw new BadRequestException("模块标识需为小写字母开头，可包含数字和下划线（2-50 位）");
        }
        return code;
    }

    private String sqlString(String value) {
        String escaped = value.replace("\0", "").replace("'", "''");
        if (databaseService.isMySql()) {
            // MySQL 默认将反斜杠视为转义符，需再次转义
            escaped = escaped.replace("\\", "\\\\");
        }
        return "'" + escaped + "'";
    }

    private String displayText(BillboardField field, Object value) {
        if (value == null) {
            return null;
        }
        if (field != null && field.getInputType() == FieldInputType.BOOLEAN) {
            boolean truthy = value instanceof Boolean b ? b
                    : value instanceof Number n ? n.intValue() != 0
                    : "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
            return truthy ? "是" : "否";
        }
        return String.valueOf(value);
    }

    private Object jdbcValue(Object value, BillboardField field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof byte[]) {
            return null;
        }
        if (field.getInputType() == FieldInputType.BOOLEAN) {
            if (value instanceof Boolean b) {
                return b;
            }
            if (value instanceof Number n) {
                return n.intValue() != 0;
            }
            String text = String.valueOf(value);
            return "1".equals(text) || "true".equalsIgnoreCase(text);
        }
        if (value instanceof Number || value instanceof BigDecimal || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    // ==================== 内部：SQL / 查询 ====================

    private List<BillboardField> enabledFields() {
        try {
            List<Long> moduleIds = enabledFieldModules().stream().map(MaintenanceModule::getId).toList();
            if (moduleIds.isEmpty()) {
                return List.of();
            }
            Set<String> existing = databaseService.existingColumns(BILLBOARD_TABLE);
            return fieldRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                    .filter(f -> moduleIds.contains(f.getModuleId()))
                    // 待同步字段（物理列不存在）不参与校验与写入，避免阻塞广告牌保存
                    .filter(f -> existing.contains(f.getFieldName().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (Exception e) {
            log.debug("扩展字段元数据表不可用，忽略扩展字段：{}", e.getMessage());
            return List.of();
        }
    }

    private List<BillboardField> allFields() {
        try {
            return fieldRepository.findAllByOrderBySortOrderAscIdAsc();
        } catch (Exception e) {
            log.debug("扩展字段元数据表不可用：{}", e.getMessage());
            return List.of();
        }
    }

    private List<MaintenanceModule> enabledFieldModules() {
        return moduleRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(MaintenanceModule::isEnabled)
                .filter(m -> m.getType().isFieldModule())
                .toList();
    }

    private String selectColumns(List<BillboardField> fields) {
        return fields.stream()
                .map(f -> databaseService.quote(f.getFieldName()))
                .collect(Collectors.joining(", "));
    }

    private Map<String, Object> mapRow(java.sql.ResultSet rs, List<BillboardField> fields) throws java.sql.SQLException {
        return mapRow(rs, fields, 1);
    }

    private Map<String, Object> mapRow(java.sql.ResultSet rs, List<BillboardField> fields, int startIndex)
            throws java.sql.SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            BillboardField field = fields.get(i);
            Object value = rs.getObject(startIndex + i);
            map.put(field.getFieldName(), jdbcValue(value, field));
        }
        return map;
    }

    private MaintenanceModule requireModule(Long id) {
        return moduleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("数据库维护子模块不存在（ID=" + id + "）"));
    }

    private BillboardField requireField(Long id) {
        return fieldRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("扩展字段不存在（ID=" + id + "）"));
    }

    private MaintenanceModuleVO toModuleVO(MaintenanceModule module, long fieldCount) {
        return new MaintenanceModuleVO(
                module.getId(),
                module.getName(),
                module.getCode(),
                module.getType().name(),
                module.getType().getLabel(),
                module.getDescription(),
                module.getSortOrder(),
                module.isEnabled(),
                module.isBuiltin(),
                fieldCount,
                module.getCreatedAt(),
                module.getUpdatedAt());
    }

    /**
     * 新增物理列：
     *   - 可空列直接添加；
     *   - 非空列有默认值：NOT NULL DEFAULT；
     *   - 非空列无默认值：先以可空列添加并回填空值，再改为 NOT NULL（兼容 TEXT，无 DEFAULT 限制）。
     */
    private void addPhysicalColumn(BillboardField field, String defaultLiteral) {
        String table = BILLBOARD_TABLE;
        String name = field.getFieldName();
        String type = field.getColumnType();
        String label = field.getLabel();
        boolean nullable = field.isNullable();
        String literal = defaultLiteral;
        if (!nullable && literal == null) {
            // 非空列必须有数据库默认值，否则 JPA 插入主记录时该列无值会失败
            literal = implicitNotNullDefaultLiteral(field.getInputType());
        }
        databaseService.addColumn(table, name, type, label, literal, nullable);
    }

    /**
     * 非空字段未填写默认值时，数据库使用类型空值兜底；
     * 应用层仍会在保存广告牌时要求填写（或使用该默认值）。
     */
    private String implicitNotNullDefaultLiteral(FieldInputType type) {
        if (type == FieldInputType.TEXTAREA) {
            // MySQL 8.0.13+ 允许 TEXT 使用表达式默认值 ('')
            return databaseService.isMySql() ? "('')" : "''";
        }
        return emptyValueLiteral(type);
    }

    /** 非空列回填时使用的类型空值字面量。 */
    private String emptyValueLiteral(FieldInputType type) {
        if (type == null) {
            return sqlString("");
        }
        return switch (type) {
            case INTEGER, BOOLEAN, DECIMAL -> "0";
            case DATE -> sqlString(LocalDate.of(1970, 1, 1).toString());
            case DATETIME -> sqlString("1970-01-01 00:00:00");
            default -> sqlString("");
        };
    }

    private String normalizeSection(String raw) {
        if (raw == null || raw.isBlank()) {
            return "extension";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!FORM_SECTIONS.contains(value)) {
            throw new BadRequestException("表单分区不合法：" + raw);
        }
        return value;
    }

    private String normalizeWidth(String raw) {
        if (raw == null || raw.isBlank()) {
            return "half";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!FORM_WIDTHS.contains(value)) {
            throw new BadRequestException("表单栏宽仅支持 half / full");
        }
        return value;
    }

    private String systemSection(String column) {
        return switch (column.toLowerCase(Locale.ROOT)) {
            case "name", "code", "status" -> "basic";
            case "road", "section", "address", "longitude", "latitude" -> "location";
            case "price", "price_unit", "price_note" -> "price";
            case "size", "material", "visible_area", "audience" -> "params";
            default -> "extension";
        };
    }

    private BillboardColumnVO toColumnVO(DbColumnVO column, BillboardField field,
                                         MaintenanceModule module, boolean synced) {
        String type = column != null ? column.type() : field.getColumnType();
        String comment = column != null ? column.comment() : field.getLabel();
        boolean nullable = column != null ? column.nullable() : field.isNullable();
        String defaultValue = column != null ? column.defaultValue() : field.getDefaultValue();
        String key = column != null ? column.key() : null;
        String extra = column != null ? column.extra() : null;
        boolean primaryKey = column != null && column.primaryKey();
        return new BillboardColumnVO(
                field.getFieldName(),
                type,
                comment,
                nullable,
                defaultValue,
                key,
                extra,
                primaryKey,
                "CUSTOM",
                field.getId(),
                field.getModuleId(),
                module != null ? module.getName() : "未知子模块",
                module != null ? module.getType().name() : null,
                field.getLabel(),
                field.getInputType() != null ? field.getInputType().name() : null,
                field.getInputType() != null ? field.getInputType().getLabel() : null,
                field.getLength(),
                field.getDecimals(),
                field.isRequired(),
                field.isVisible(),
                BillboardFieldVO.parseOptions(field.getOptionsText()),
                field.getPlaceholder(),
                field.getSortOrder(),
                field.isEnabled(),
                field.getSection(),
                field.getWidth(),
                synced);
    }

    private BillboardFieldVO toFieldVO(BillboardField field, MaintenanceModule module, boolean synced) {
        return BillboardFieldVO.from(field, module.getName(), module.getType().name(), synced);
    }

    private int nextModuleSort() {
        return moduleRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .mapToInt(MaintenanceModule::getSortOrder)
                .max()
                .orElse(0) + 10;
    }

    private int nextFieldSort(Long moduleId) {
        return fieldRepository.findByModuleIdOrderBySortOrderAscIdAsc(moduleId).stream()
                .mapToInt(BillboardField::getSortOrder)
                .max()
                .orElse(0) + 10;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record BuiltinModule(
            String code,
            String name,
            MaintenanceModuleType type,
            String description,
            int sortOrder) {
    }
}
