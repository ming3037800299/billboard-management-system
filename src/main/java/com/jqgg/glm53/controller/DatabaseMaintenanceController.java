package com.jqgg.glm53.controller;

import com.jqgg.glm53.dto.ApiResult;
import com.jqgg.glm53.dto.BillboardColumnVO;
import com.jqgg.glm53.dto.BillboardFieldForm;
import com.jqgg.glm53.dto.BillboardFieldGroupVO;
import com.jqgg.glm53.dto.BillboardFieldVO;
import com.jqgg.glm53.dto.ColumnCommentForm;
import com.jqgg.glm53.dto.DbDatabaseListVO;
import com.jqgg.glm53.dto.TableColumnForm;
import com.jqgg.glm53.dto.DbOverviewVO;
import com.jqgg.glm53.dto.DbSearchResultVO;
import com.jqgg.glm53.dto.DbTableVO;
import com.jqgg.glm53.dto.FieldLayoutForm;
import com.jqgg.glm53.dto.FieldStatsVO;
import com.jqgg.glm53.dto.SystemColumnForm;
import com.jqgg.glm53.dto.MaintenanceModuleForm;
import com.jqgg.glm53.dto.MaintenanceModuleVO;
import com.jqgg.glm53.service.BillboardFieldService;
import com.jqgg.glm53.service.DatabaseMaintenanceService;
import com.jqgg.glm53.service.MultiDatabaseMaintenanceService;
import com.jqgg.glm53.service.SystemColumnService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据库维护接口（后台，全部需要 X-Admin-Token）：
 *   GET    /api/admin/db/overview                 数据库概览 / DDL 权限
 *   GET    /api/admin/db/tables                   全部表结构与字段注释
 *   GET    /api/admin/db/search                   按表/字段/注释/数据内容检索
 *   GET    /api/admin/db/modules                  子模块列表
 *   POST   /api/admin/db/modules                  新建子模块
 *   PUT    /api/admin/db/modules/{id}             编辑子模块
 *   DELETE /api/admin/db/modules/{id}             删除子模块
 *   GET    /api/admin/db/modules/{id}/fields      子模块下的字段列表
 *   POST   /api/admin/db/modules/{id}/fields      新增字段（同步 ALTER TABLE）
 *   PUT    /api/admin/db/fields/{id}              编辑字段展示属性
 *   DELETE /api/admin/db/fields/{id}              删除字段（同步 DROP COLUMN）
 *   POST   /api/admin/db/fields/{id}/sync         修复同步（列缺失时重新新增）
 *   GET    /api/admin/db/billboard-fields         广告牌表单字段定义（按子模块分组）
 */
@RestController
@RequestMapping("/api/admin/db")
public class DatabaseMaintenanceController {

    private final DatabaseMaintenanceService databaseService;
    private final MultiDatabaseMaintenanceService multiDatabaseService;
    private final SystemColumnService systemColumnService;
    private final BillboardFieldService fieldService;

    public DatabaseMaintenanceController(DatabaseMaintenanceService databaseService,
                                         MultiDatabaseMaintenanceService multiDatabaseService,
                                         SystemColumnService systemColumnService,
                                         BillboardFieldService fieldService) {
        this.databaseService = databaseService;
        this.multiDatabaseService = multiDatabaseService;
        this.systemColumnService = systemColumnService;
        this.fieldService = fieldService;
    }

    // ------------------- 多数据库维护 -------------------

    /** 维护账号可见的数据库列表（默认库 + 其他业务库）。 */
    @GetMapping("/databases")
    public ApiResult<DbDatabaseListVO> databases() {
        return ApiResult.ok(multiDatabaseService.databases());
    }

    /** 指定数据库的概览。 */
    @GetMapping("/databases/{database}/overview")
    public ApiResult<DbOverviewVO> databaseOverview(@PathVariable String database) {
        return ApiResult.ok(multiDatabaseService.overview(database));
    }

    /** 指定数据库的表结构。 */
    @GetMapping("/databases/{database}/tables")
    public ApiResult<List<DbTableVO>> databaseTables(@PathVariable String database,
                                                     @RequestParam(required = false) String keyword) {
        return ApiResult.ok(multiDatabaseService.tables(database, keyword));
    }

    /** 指定数据库内检索。 */
    @GetMapping("/databases/{database}/search")
    public ApiResult<DbSearchResultVO> databaseSearch(@PathVariable String database,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false, defaultValue = "false") boolean includeData) {
        return ApiResult.ok(multiDatabaseService.search(database, keyword, includeData));
    }

    /** 跨数据库检索字段所属库（不扫描表内数据）。 */
    @GetMapping("/search-all")
    public ApiResult<DbSearchResultVO> searchAll(@RequestParam(required = false) String keyword) {
        return ApiResult.ok(multiDatabaseService.searchAll(keyword));
    }

    /** 在指定数据库的指定表中新增字段。 */
    @PostMapping("/databases/{database}/tables/{table}/columns")
    public ApiResult<List<DbTableVO>> addTableColumn(@PathVariable String database,
                                                     @PathVariable String table,
                                                     @Valid @RequestBody TableColumnForm form) {
        return ApiResult.ok(multiDatabaseService.addColumn(database, table, form));
    }

    @GetMapping("/overview")
    public ApiResult<DbOverviewVO> overview() {
        return ApiResult.ok(databaseService.overview());
    }

    @GetMapping("/tables")
    public ApiResult<List<DbTableVO>> tables(@RequestParam(required = false) String keyword) {
        return ApiResult.ok(databaseService.tables(keyword));
    }

    @GetMapping("/search")
    public ApiResult<DbSearchResultVO> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean includeData) {
        return ApiResult.ok(databaseService.search(keyword, includeData));
    }

    // ------------------- 子模块 -------------------

    @GetMapping("/modules")
    public ApiResult<List<MaintenanceModuleVO>> modules() {
        return ApiResult.ok(fieldService.listModules());
    }

    @PostMapping("/modules")
    public ApiResult<MaintenanceModuleVO> createModule(@Valid @RequestBody MaintenanceModuleForm form) {
        return ApiResult.ok(fieldService.createModule(form));
    }

    @PutMapping("/modules/{id}")
    public ApiResult<MaintenanceModuleVO> updateModule(@PathVariable Long id,
                                                       @Valid @RequestBody MaintenanceModuleForm form) {
        return ApiResult.ok(fieldService.updateModule(id, form));
    }

    @DeleteMapping("/modules/{id}")
    public ApiResult<Void> deleteModule(@PathVariable Long id) {
        fieldService.deleteModule(id);
        return ApiResult.ok();
    }

    // ------------------- 字段 / 属性 -------------------

    @GetMapping("/modules/{id}/fields")
    public ApiResult<List<BillboardFieldVO>> fields(@PathVariable Long id) {
        return ApiResult.ok(fieldService.listFields(id));
    }

    @PostMapping("/modules/{id}/fields")
    public ApiResult<BillboardFieldVO> addField(@PathVariable Long id,
                                               @Valid @RequestBody BillboardFieldForm form) {
        return ApiResult.ok(fieldService.addField(id, form));
    }

    @PutMapping("/fields/{id}")
    public ApiResult<BillboardFieldVO> updateField(@PathVariable Long id,
                                                   @Valid @RequestBody BillboardFieldForm form) {
        return ApiResult.ok(fieldService.updateField(id, form));
    }

    @DeleteMapping("/fields/{id}")
    public ApiResult<Void> deleteField(@PathVariable Long id) {
        fieldService.deleteField(id);
        return ApiResult.ok();
    }

    /** 更新字段启用状态 / 表单分区 / 栏宽 / 排序（只改展示配置，不改表结构）。 */
    @PutMapping("/fields/{id}/layout")
    public ApiResult<BillboardFieldVO> updateFieldLayout(@PathVariable Long id,
                                                        @Valid @RequestBody FieldLayoutForm form) {
        return ApiResult.ok(fieldService.updateLayout(id, form));
    }

    @PostMapping("/fields/{id}/sync")
    public ApiResult<BillboardFieldVO> syncField(@PathVariable Long id) {
        return ApiResult.ok(fieldService.syncField(id));
    }

    /** billboard 表全部实际字段（系统字段 + 扩展字段），字段页展示用。 */
    @GetMapping("/billboard-columns")
    public ApiResult<List<BillboardColumnVO>> billboardColumns() {
        return ApiResult.ok(fieldService.billboardColumns());
    }

    /** 字段数据概况：总记录 / 空值 / 不同值 / 示例值。 */
    @GetMapping("/billboard-columns/{name}/stats")
    public ApiResult<FieldStatsVO> fieldStats(@PathVariable String name) {
        return ApiResult.ok(databaseService.fieldStats("billboard", name));
    }

    /** 修改系统字段（billboard 核心列）的类型 / 长度 / 可空性 / 注释。 */
    @PutMapping("/billboard-columns/{name}/definition")
    public ApiResult<Void> updateSystemColumn(@PathVariable String name,
                                             @Valid @RequestBody SystemColumnForm form) {
        systemColumnService.alter(name, form);
        return ApiResult.ok();
    }

    /** 修改数据库列注释；扩展字段会同步更新显示名称。 */
    @PutMapping("/billboard-columns/{name}")
    public ApiResult<BillboardColumnVO> updateColumnComment(@PathVariable String name,
                                                            @Valid @RequestBody ColumnCommentForm form) {
        return ApiResult.ok(fieldService.updateBillboardColumnComment(name, form.getComment()));
    }

    /** 后台广告牌编辑表单：按子模块分组返回全部扩展字段定义。 */
    @GetMapping("/billboard-fields")
    public ApiResult<List<BillboardFieldGroupVO>> billboardFields() {
        return ApiResult.ok(fieldService.listFieldGroups());
    }
}
