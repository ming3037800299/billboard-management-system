package com.jqgg.glm53.controller;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.dto.ApiResult;
import com.jqgg.glm53.dto.BillboardVO;
import com.jqgg.glm53.dto.PublicBillboardFieldVO;
import com.jqgg.glm53.service.ApiConfigService;
import com.jqgg.glm53.service.BillboardFieldService;
import com.jqgg.glm53.service.BillboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 前台公开接口（无需登录）：
 *   GET /api/site-config              站点配置（站名、地图 Key）
 *   GET /api/billboards               广告牌列表（支持路段 / 状态 / 价格 / 关键词筛选与排序）
 *   GET /api/billboards/roads         去重路段列表（筛选下拉数据源）
 *   GET /api/billboards/custom-fields 数据库维护模块配置的扩展字段元数据
 *   GET /api/billboards/{id}          广告牌详情
 */
@RestController
@RequestMapping("/api")
public class PublicBillboardController {

    private final BillboardService billboardService;
    private final BillboardFieldService billboardFieldService;
    private final AppProperties appProperties;
    private final ApiConfigService apiConfigService;

    public PublicBillboardController(BillboardService billboardService,
                                     BillboardFieldService billboardFieldService,
                                     AppProperties appProperties,
                                     ApiConfigService apiConfigService) {
        this.billboardService = billboardService;
        this.billboardFieldService = billboardFieldService;
        this.appProperties = appProperties;
        this.apiConfigService = apiConfigService;
    }

    /** 站点配置：前台加载地图所需的 Key 等公开信息。 */
    @GetMapping("/site-config")
    public ApiResult<Map<String, String>> siteConfig() {
        return ApiResult.ok(Map.of(
                "siteName", appProperties.getSiteName(),
                "mapAk", apiConfigService.effectiveMapKey()));
    }

    /** 广告牌列表（仅返回未删除数据）。 */
    @GetMapping("/billboards")
    public ApiResult<List<BillboardVO>> list(
            @RequestParam(required = false) String road,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "newest") String sort) {

        var billboards = billboardService
                .list(new BillboardService.Query(road, status, minPrice, maxPrice, keyword, sort, false));
        // 一次性批量读取扩展字段；不要按每条广告牌循环查询库表结构。
        Map<Long, Map<String, Object>> extraValues = billboardFieldService.readValues(
                billboards.stream().map(b -> b.getId()).toList());
        List<BillboardVO> list = billboards.stream()
                .map(b -> BillboardVO.from(b, extraValues.getOrDefault(b.getId(), Map.of())))
                .toList();
        return ApiResult.ok(list);
    }

    /** 去重路段列表（字面路径优先于 /{id} 模式匹配）。 */
    @GetMapping("/billboards/roads")
    public ApiResult<List<String>> roads() {
        return ApiResult.ok(billboardService.distinctRoads());
    }

    /** 扩展字段元数据：前台详情据此展示数据库维护模块新增的字段/属性。 */
    @GetMapping("/billboards/custom-fields")
    public ApiResult<List<PublicBillboardFieldVO>> customFields() {
        return ApiResult.ok(billboardFieldService.publicFields());
    }

    /** 广告牌详情。 */
    @GetMapping("/billboards/{id}")
    public ApiResult<BillboardVO> detail(@PathVariable Long id) {
        return ApiResult.ok(BillboardVO.from(
                billboardService.detail(id, false),
                billboardFieldService.readValues(id)));
    }
}
