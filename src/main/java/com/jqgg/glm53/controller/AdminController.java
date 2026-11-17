package com.jqgg.glm53.controller;

import com.jqgg.glm53.config.AdminAuthInterceptor;
import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.dto.ApiResult;
import com.jqgg.glm53.dto.BillboardVO;
import com.jqgg.glm53.dto.LoginRequest;
import com.jqgg.glm53.dto.SaveBillboardForm;
import com.jqgg.glm53.dto.ZipImportResultVO;
import com.jqgg.glm53.entity.Billboard;
import com.jqgg.glm53.entity.BillboardStatus;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.service.AdminTokenService;
import com.jqgg.glm53.service.BillboardArchiveImportService;
import com.jqgg.glm53.service.BillboardFieldService;
import com.jqgg.glm53.service.BillboardImageMetaService;
import com.jqgg.glm53.service.BillboardService;
import com.jqgg.glm53.service.FileStorageService;
import com.jqgg.glm53.service.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台管理接口（登录除外均需 X-Admin-Token 令牌，见 AdminAuthInterceptor）：
 *   POST   /api/admin/login                密码验证，签发令牌
 *   POST   /api/admin/logout               退出登录，作废令牌
 *   GET    /api/admin/billboards           全量列表（可含已删除，支持筛选）
 *   POST   /api/admin/billboards           新增（multipart：字段 + 图片文件 / 已导入图片 + 分类）
 *   PUT    /api/admin/billboards/{id}      编辑（multipart：字段 + 新图 + 保留旧图 + 分类）
 *   POST   /api/admin/billboards/images/import  压缩包批量导入并自动分类（multipart：file）
 *   DELETE /api/admin/billboards/images/import/{sessionId}  清理未保存的导入临时文件
 *   DELETE /api/admin/billboards/{id}      软删除
 *   POST   /api/admin/billboards/{id}/restore  恢复软删除
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** 合法的计费周期 */
    private static final Set<String> PRICE_UNITS = Set.of("元/月", "元/年");

    /** 保留旧图的安全约束：仅允许站内相对路径（防注入外链/协议地址） */
    private static final Pattern SAFE_IMAGE_PATH = Pattern.compile("^/[A-Za-z0-9_\\-./]+$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BillboardService billboardService;
    private final BillboardFieldService billboardFieldService;
    private final FileStorageService fileStorageService;
    private final BillboardImageMetaService imageMetaService;
    private final BillboardArchiveImportService archiveImportService;
    private final AdminTokenService adminTokenService;
    private final KnowledgeService knowledgeService;
    private final AppProperties appProperties;

    public AdminController(BillboardService billboardService,
                           BillboardFieldService billboardFieldService,
                           FileStorageService fileStorageService,
                           BillboardImageMetaService imageMetaService,
                           BillboardArchiveImportService archiveImportService,
                           AdminTokenService adminTokenService,
                           KnowledgeService knowledgeService,
                           AppProperties appProperties) {
        this.billboardService = billboardService;
        this.billboardFieldService = billboardFieldService;
        this.fileStorageService = fileStorageService;
        this.imageMetaService = imageMetaService;
        this.archiveImportService = archiveImportService;
        this.adminTokenService = adminTokenService;
        this.knowledgeService = knowledgeService;
        this.appProperties = appProperties;
    }

    // ------------------- 登录 / 退出 -------------------

    /** 密码验证：通过则返回访问令牌。 */
    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        String token = adminTokenService.login(request.password());
        return ApiResult.ok(Map.of(
                "token", token,
                "tokenTtlHours", appProperties.getAdmin().getTokenTtlHours()));
    }

    /** 退出登录：作废当前令牌。 */
    @PostMapping("/logout")
    public ApiResult<Void> logout(@RequestHeader(AdminAuthInterceptor.TOKEN_HEADER) String token) {
        adminTokenService.logout(token);
        return ApiResult.ok();
    }

    // ------------------- 查询 -------------------

    /** 管理端全量列表（includeDeleted=true 时包含软删除数据，便于查看与恢复）。 */
    @GetMapping("/billboards")
    public ApiResult<List<BillboardVO>> list(
            @RequestParam(required = false) String road,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "newest") String sort,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted) {

        List<Billboard> billboards = billboardService
                .list(new BillboardService.Query(road, status, minPrice, maxPrice, keyword, sort, includeDeleted));
        Map<Long, Map<String, Object>> extraValues = billboardFieldService.readValues(
                billboards.stream().map(Billboard::getId).toList());
        List<BillboardVO> list = billboards.stream()
                .map(b -> BillboardVO.from(b, extraValues.getOrDefault(b.getId(), Map.of())))
                .toList();
        return ApiResult.ok(list);
    }

    // ------------------- 新增 / 编辑 -------------------

    /** 新增广告牌：multipart 表单（字段 + 可选图片文件 / 已导入图片 + 图片分类 + 动态扩展值）。 */
    @PostMapping(value = "/billboards", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<BillboardVO> create(@Valid @ModelAttribute SaveBillboardForm form) {
        List<String> uploaded = fileStorageService.store(form.getImages());
        PreparedImages images = prepareImages(parseKeepImages(form.getKeepImages()), uploaded,
                form.getImageMeta());
        Billboard billboard = toEntity(form, images.urls(), images.metaJson());
        Map<String, Object> extraValues = parseExtraValues(form.getExtraValues());
        Billboard saved = billboardService.create(billboard, extraValues);
        knowledgeService.indexBillboardSafelyAsync(saved.getId());
        return ApiResult.ok(BillboardVO.from(saved, billboardFieldService.readValues(saved.getId())));
    }

    /** 编辑广告牌：保留的旧图（keepImages）与本次新上传的图片合并，分类/封面随图片一起保存。 */
    @PutMapping(value = "/billboards/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<BillboardVO> update(@PathVariable Long id,
                                         @Valid @ModelAttribute SaveBillboardForm form) {
        List<String> kept = parseKeepImages(form.getKeepImages());
        List<String> uploaded = fileStorageService.store(form.getImages());
        PreparedImages images = prepareImages(kept, uploaded, form.getImageMeta());
        Billboard billboard = toEntity(form, images.urls(), images.metaJson());
        Map<String, Object> extraValues = parseExtraValues(form.getExtraValues());
        Billboard saved = billboardService.update(id, billboard, extraValues);
        knowledgeService.indexBillboardSafelyAsync(id);
        return ApiResult.ok(BillboardVO.from(saved, billboardFieldService.readValues(saved.getId())));
    }

    // ------------------- 压缩包批量导入 -------------------

    /** 上传 zip 压缩包：解压、校验、过滤非图片并自动分类，返回分组预览所需结果（不落广告牌）。 */
    @PostMapping(value = "/billboards/images/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ZipImportResultVO> importArchive(@RequestParam("file") MultipartFile file) {
        return ApiResult.ok(archiveImportService.importZip(file));
    }

    /** 直接选择多张图片：与压缩包导入同一套校验 / 自动分类逻辑（不落广告牌）。 */
    @PostMapping(value = "/billboards/images/classify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ZipImportResultVO> classifyImages(@RequestParam("files") List<MultipartFile> files) {
        return ApiResult.ok(archiveImportService.importFiles(files));
    }

    /** 清理未保存的压缩包导入临时目录（保存广告牌时图片会转存到常规目录，无需再清理）。 */
    @DeleteMapping("/billboards/images/import/{sessionId}")
    public ApiResult<Void> cleanupImport(@PathVariable String sessionId) {
        archiveImportService.cleanup(sessionId);
        return ApiResult.ok();
    }

    /** 合并保留图 / 新上传图，并把压缩包临时目录中的图片转存到常规年月目录。 */
    private PreparedImages prepareImages(List<String> kept, List<String> uploaded, String submittedMeta) {
        List<String> all = new ArrayList<>(kept.size() + uploaded.size());
        all.addAll(kept);
        all.addAll(uploaded);
        BillboardImageMetaService.Normalized normalized =
                imageMetaService.normalize(submittedMeta, all);
        List<String> adopted = new ArrayList<>(normalized.imageUrls().size());
        java.util.Map<String, String> mapping = new java.util.LinkedHashMap<>();
        for (String url : normalized.imageUrls()) {
            String adoptedUrl = fileStorageService.adopt(url);
            mapping.put(url, adoptedUrl);
            adopted.add(adoptedUrl);
        }
        return new PreparedImages(adopted, imageMetaService.remap(normalized.metaJson(), mapping));
    }

    /** 图片准备结果：封面置顶后的最终地址与对应的分类元信息。 */
    private record PreparedImages(List<String> urls, String metaJson) {
    }

    // ------------------- 删除 / 恢复 -------------------

    /** 软删除广告牌（数据保留在库中，可通过恢复接口找回）。 */
    @DeleteMapping("/billboards/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        billboardService.delete(id);
        knowledgeService.removeSafely(id);
        return ApiResult.ok();
    }

    /** 恢复软删除的广告牌。 */
    @PostMapping("/billboards/{id}/restore")
    public ApiResult<BillboardVO> restore(@PathVariable Long id) {
        Billboard restored = billboardService.restore(id);
        knowledgeService.indexBillboardSafelyAsync(id);
        return ApiResult.ok(BillboardVO.from(restored, billboardFieldService.readValues(restored.getId())));
    }

    // ------------------- 表单 → 实体 -------------------

    /**
     * 把 multipart 表单字段转换为实体并做业务校验。
     *
     * @param form       表单字段
     * @param imageUrls  最终的图片 URL 列表（封面已置顶）
     * @param imageMeta  与 imageUrls 对应的分类 / 封面元信息 JSON
     */
    private Billboard toEntity(SaveBillboardForm form, List<String> imageUrls, String imageMeta) {
        Billboard b = new Billboard();
        b.setName(form.getName().trim());
        b.setCode(form.getCode().trim());
        b.setRoad(trimToNull(form.getRoad()));
        b.setSection(trimToNull(form.getSection()));
        b.setAddress(trimToNull(form.getAddress()));
        b.setLongitude(parseCoordinate(form.getLongitude(), "经度", new BigDecimal("-180"), new BigDecimal("180")));
        b.setLatitude(parseCoordinate(form.getLatitude(), "纬度", new BigDecimal("-90"), new BigDecimal("90")));
        b.setImages(BillboardVO.writeImages(imageUrls));
        b.setImageMeta(imageMeta);
        b.setPrice(parsePrice(form.getPrice()));
        b.setPriceUnit(parsePriceUnit(form.getPriceUnit()));
        b.setPriceNote(trimToNull(form.getPriceNote()));
        b.setSize(trimToNull(form.getSize()));
        b.setMaterial(trimToNull(form.getMaterial()));
        b.setVisibleArea(trimToNull(form.getVisibleArea()));
        b.setAudience(trimToNull(form.getAudience()));
        b.setStatus(parseStatus(form.getStatus()));
        b.setDescription(trimToNull(form.getDescription()));
        return b;
    }

    /** 解析经纬度：空串视为未填写，非法值 / 超出范围则报 400。 */
    private BigDecimal parseCoordinate(String raw, String label, BigDecimal min, BigDecimal max) {
        if (isBlank(raw)) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException(label + "格式不正确：" + raw);
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new BadRequestException(label + "超出合理范围（" + min + " ~ " + max + "）");
        }
        return value;
    }

    /** 解析报价：空串视为未填写，必须为不小于 0 的数字。 */
    private BigDecimal parsePrice(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("租赁报价格式不正确：" + raw);
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("租赁报价不能为负数");
        }
        return value;
    }

    /** 计费周期：默认 元/月，仅接受 元/月 / 元/年。 */
    private String parsePriceUnit(String raw) {
        if (isBlank(raw)) {
            return "元/月";
        }
        String unit = raw.trim();
        if (!PRICE_UNITS.contains(unit)) {
            throw new BadRequestException("计费周期仅支持：元/月、元/年");
        }
        return unit;
    }

    /** 租赁状态：必须为合法枚举名。 */
    private BillboardStatus parseStatus(String raw) {
        if (isBlank(raw)) {
            return BillboardStatus.AVAILABLE;
        }
        try {
            return BillboardStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("租赁状态不合法：" + raw);
        }
    }

    /** 解析编辑表单中保留的旧图 URL 数组（JSON 字符串），并校验路径安全。 */
    private List<String> parseKeepImages(String keepImagesJson) {
        if (isBlank(keepImagesJson)) {
            return List.of();
        }
        List<String> kept;
        try {
            kept = MAPPER.readValue(keepImagesJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new BadRequestException("保留图片参数格式错误");
        }
        if (kept == null) {
            return List.of();
        }
        for (String url : kept) {
            if (url == null || !SAFE_IMAGE_PATH.matcher(url).matches()) {
                throw new BadRequestException("保留图片路径不合法：" + url);
            }
        }
        return kept;
    }

    /** 解析动态扩展字段值（JSON 对象：字段名 → 值）。 */
    private Map<String, Object> parseExtraValues(String extraValuesJson) {
        if (isBlank(extraValuesJson)) {
            return Map.of();
        }
        try {
            Map<String, Object> values = MAPPER.readValue(extraValuesJson, new TypeReference<>() {
            });
            return values == null ? Map.of() : values;
        } catch (Exception e) {
            throw new BadRequestException("扩展字段参数格式错误");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
