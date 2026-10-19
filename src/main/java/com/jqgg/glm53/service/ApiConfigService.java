package com.jqgg.glm53.service;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.dto.ApiConfigForm;
import com.jqgg.glm53.dto.ApiConfigVO;
import com.jqgg.glm53.dto.ApiTestRequest;
import com.jqgg.glm53.dto.ApiTestResultVO;
import com.jqgg.glm53.entity.ApiConfig;
import com.jqgg.glm53.entity.ApiType;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.exception.NotFoundException;
import com.jqgg.glm53.repository.ApiConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台 API 管理服务。
 *
 * <p>职责：
 * <ul>
 *   <li>维护向量模型、检索生成模型、地图及其他接口的地址 / Key / 模型参数；</li>
 *   <li>同一类型最多一个默认配置，列表接口对 Key 脱敏；</li>
 *   <li>为业务服务提供运行时配置解析：数据库优先，无启用配置时回退 application.yml；</li>
 *   <li>对已保存或表单中的配置发起真实连通性测试。</li>
 * </ul>
 */
@Service
public class ApiConfigService {

    private static final Logger log = LoggerFactory.getLogger(ApiConfigService.class);

    /** 各类型允许的服务商协议 */
    private static final Map<ApiType, Set<String>> PROVIDERS = new EnumMap<>(ApiType.class);

    /** 各类型默认服务商 */
    private static final Map<ApiType, String> DEFAULT_PROVIDERS = new EnumMap<>(ApiType.class);

    private static final Map<String, String> PROVIDER_LABELS = Map.of(
            "OLLAMA", "Ollama 原生",
            "OPENAI", "OpenAI 兼容",
            "TENCENT", "腾讯位置服务",
            "HTTP", "HTTP 接口");

    private static final String TENCENT_BASE_URL = "https://apis.map.qq.com";

    static {
        PROVIDERS.put(ApiType.EMBEDDING, Set.of("OLLAMA", "OPENAI"));
        PROVIDERS.put(ApiType.LLM, Set.of("OPENAI"));
        PROVIDERS.put(ApiType.MAP, Set.of("TENCENT"));
        PROVIDERS.put(ApiType.OTHER, Set.of("HTTP"));

        DEFAULT_PROVIDERS.put(ApiType.EMBEDDING, "OLLAMA");
        DEFAULT_PROVIDERS.put(ApiType.LLM, "OPENAI");
        DEFAULT_PROVIDERS.put(ApiType.MAP, "TENCENT");
        DEFAULT_PROVIDERS.put(ApiType.OTHER, "HTTP");
    }

    private final ApiConfigRepository repository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    /** 表缺失时只提示一次，避免每次问答都刷日志 */
    private final AtomicBoolean databaseWarned = new AtomicBoolean(false);

    public ApiConfigService(ApiConfigRepository repository,
                            AppProperties appProperties,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    // ==================== 列表 / 增删改 ====================

    /** 后台列表；type 为空时返回全部类型。 */
    public List<ApiConfigVO> list(String type) {
        ApiType filter = type == null || type.isBlank() ? null : parseType(type);
        List<ApiConfig> rows;
        try {
            rows = filter == null
                    ? repository.findAllByOrderByTypeAscPreferredDescIdAsc()
                    : repository.findByTypeOrdered(filter);
        } catch (DataAccessException e) {
            throw databaseUnavailable(e);
        }
        Map<ApiType, Long> effectiveIds = effectiveIds();
        return rows.stream()
                .map(row -> toVO(row, effectiveIds.get(row.getType())))
                .toList();
    }

    /** 新增配置；同类第一条启用配置自动成为默认。 */
    @Transactional
    public ApiConfigVO create(ApiConfigForm form) {
        ApiConfig config = new ApiConfig();
        applyForm(config, form, true);
        try {
            if (repository.existsByNameIgnoreCase(config.getName())) {
                throw new BadRequestException("配置名称已存在：" + config.getName());
            }
            if (config.isPreferred()) {
                config.setEnabled(true);
                clearPreferred(config.getType(), null);
            } else if (config.isEnabled() && !repository.existsByTypeAndEnabledTrue(config.getType())) {
                config.setPreferred(true);
            }
            ApiConfig saved = repository.save(config);
            ensureDefault(saved.getType());
            return toVO(saved, effectiveId(saved.getType()));
        } catch (DataAccessException e) {
            throw databaseUnavailable(e);
        }
    }

    /** 编辑配置；apiKey 留空表示保持原值。 */
    @Transactional
    public ApiConfigVO update(Long id, ApiConfigForm form) {
        ApiConfig config = load(id);
        ApiType oldType = config.getType();
        boolean wasPreferred = config.isPreferred();
        applyForm(config, form, false);
        try {
            if (repository.existsByNameIgnoreCaseAndIdNot(config.getName(), id)) {
                throw new BadRequestException("配置名称已存在：" + config.getName());
            }
            if (config.isPreferred()) {
                config.setEnabled(true);
                clearPreferred(config.getType(), id);
            }
            ApiConfig saved = repository.save(config);
            if (wasPreferred && !saved.getType().equals(oldType)) {
                ensureDefault(oldType);
            }
            ensureDefault(saved.getType());
            return toVO(saved, effectiveId(saved.getType()));
        } catch (DataAccessException e) {
            throw databaseUnavailable(e);
        }
    }

    /** 删除配置；删除默认项后自动把同类型第一条启用配置设为默认。 */
    @Transactional
    public void delete(Long id) {
        ApiConfig config = load(id);
        try {
            repository.delete(config);
            ensureDefault(config.getType());
        } catch (DataAccessException e) {
            throw databaseUnavailable(e);
        }
    }

    /** 查询配置类型（编辑 / 删除前判断是否需要重建向量索引）。 */
    public ApiType findType(Long id) {
        return load(id).getType();
    }

    /** 设为同类默认配置（同时启用）。 */
    @Transactional
    public ApiConfigVO setDefault(Long id) {
        ApiConfig config = load(id);
        try {
            clearPreferred(config.getType(), id);
            config.setEnabled(true);
            config.setPreferred(true);
            ApiConfig saved = repository.save(config);
            return toVO(saved, effectiveId(saved.getType()));
        } catch (DataAccessException e) {
            throw databaseUnavailable(e);
        }
    }

    // ==================== 运行时配置解析 ====================

    /**
     * 解析某类型的当前生效配置：数据库启用配置优先，找不到（或表不可用）时回退 application.yml。
     */
    public ResolvedApi resolve(ApiType type) {
        try {
            List<ApiConfig> enabled = repository.findEnabledByType(type);
            if (!enabled.isEmpty()) {
                return toResolved(enabled.get(0));
            }
        } catch (DataAccessException e) {
            warnDatabaseUnavailable(e);
        }
        return fallback(type);
    }

    /** 当前生效的地图 Key（供前台 /api/site-config 使用）。 */
    public String effectiveMapKey() {
        ResolvedApi map = resolve(ApiType.MAP);
        return map == null || map.apiKey() == null ? "" : map.apiKey();
    }

    /** 所有启用的问答模型配置（默认项排在最前）。 */
    public List<ResolvedApi> enabledLlmApis() {
        try {
            return repository.findEnabledByType(ApiType.LLM).stream()
                    .map(this::toResolved)
                    .toList();
        } catch (DataAccessException e) {
            warnDatabaseUnavailable(e);
            return List.of();
        }
    }

    /** 按 ID 查找启用中的问答模型配置。 */
    public ResolvedApi findEnabledLlm(Long id) {
        if (id == null) {
            return null;
        }
        try {
            return repository.findById(id)
                    .filter(c -> c.getType() == ApiType.LLM && c.isEnabled())
                    .map(this::toResolved)
                    .orElse(null);
        } catch (DataAccessException e) {
            warnDatabaseUnavailable(e);
            return null;
        }
    }

    /**
     * 向量模型签名：用于判断知识库索引是否需要重建。
     * 服务商 / 地址 / 模型任一变化都会触发重新向量化。
     */
    public String embeddingSignature() {
        ResolvedApi api = resolve(ApiType.EMBEDDING);
        if (api == null) {
            return "unconfigured";
        }
        return api.provider() + "|" + nullToEmpty(api.baseUrl()) + "|" + nullToEmpty(api.model());
    }

    // ==================== 连通性测试 ====================

    /** 测试已保存的配置。 */
    public ApiTestResultVO testSaved(Long id) {
        ApiConfig config = load(id);
        return doTest(config.getType(), config.getProvider(), config.getBaseUrl(),
                config.getApiKey(), config.getModel(), config.getTimeoutSeconds());
    }

    /** 测试表单中的配置；Key / 地址 / 模型留空时复用 id 对应配置。 */
    public ApiTestResultVO test(ApiTestRequest request) {
        ApiType type = parseType(request.type());
        String provider = normalizeProvider(type, request.provider());
        String baseUrl = trimToNull(request.baseUrl());
        String apiKey = trimToNull(request.apiKey());
        String model = trimToNull(request.model());
        Integer timeout = request.timeoutSeconds();

        if (request.id() != null
                && (baseUrl == null || apiKey == null || model == null || timeout == null
                || isBlank(request.provider()))) {
            ApiConfig saved = load(request.id());
            provider = isBlank(request.provider()) ? saved.getProvider() : provider;
            baseUrl = baseUrl == null ? saved.getBaseUrl() : baseUrl;
            apiKey = apiKey == null ? saved.getApiKey() : apiKey;
            model = model == null ? saved.getModel() : model;
            timeout = timeout == null ? saved.getTimeoutSeconds() : timeout;
        }
        return doTest(type, provider, baseUrl, apiKey, model, timeout);
    }

    private ApiTestResultVO doTest(ApiType type, String provider, String baseUrl,
                                   String apiKey, String model, Integer timeoutSeconds) {
        long start = System.nanoTime();
        int timeout = Math.max(2, Math.min(120,
                timeoutSeconds == null ? 30 : timeoutSeconds));

        if (type != ApiType.MAP && isBlank(baseUrl)) {
            return testFailure("请填写接口地址", start, null);
        }
        if ((type == ApiType.EMBEDDING || type == ApiType.LLM) && isBlank(model)) {
            return testFailure("请填写模型名称", start, null);
        }

        try {
            return switch (type) {
                case EMBEDDING -> testEmbedding(provider, baseUrl, apiKey, model, timeout, start);
                case LLM -> testLlm(baseUrl, apiKey, model, timeout, start);
                case MAP -> testMap(provider, baseUrl, apiKey, timeout, start);
                case OTHER -> testOther(baseUrl, apiKey, timeout, start);
            };
        } catch (Exception e) {
            return testFailure(describeFailure(e, timeout), start, null);
        }
    }

    private ApiTestResultVO testEmbedding(String provider, String baseUrl, String apiKey,
                                          String model, int timeout, long start) throws Exception {
        String url = baseUrl + ("OLLAMA".equals(provider) ? "/api/embed" : "/embeddings");
        Map<String, Object> body = Map.of("model", model, "input", List.of("ping"));
        HttpResult response = send("POST", url, authHeaders(apiKey), toJson(body), timeout);

        if (!is2xx(response.status())) {
            return testFailure("HTTP " + response.status() + "：" + snippet(response.body()), start, response.status());
        }
        JsonNode root = readJson(response.body());
        boolean ok = (root != null && root.has("embeddings") && root.get("embeddings").isArray())
                || (root != null && root.has("data") && root.get("data").isArray());
        if (!ok) {
            return testFailure("接口响应中没有向量数据，请检查模型名称或服务商协议", start, response.status());
        }
        return testSuccess("向量模型可用，已成功生成测试向量", start, response.status());
    }

    private ApiTestResultVO testLlm(String baseUrl, String apiKey, String model,
                                    int timeout, long start) throws Exception {
        String url = baseUrl + "/chat/completions";
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", "请只回复 ok")),
                "max_tokens", 8,
                "stream", false);
        HttpResult response = send("POST", url, authHeaders(apiKey), toJson(body), timeout);

        if (!is2xx(response.status())) {
            return testFailure("HTTP " + response.status() + "：" + snippet(response.body()), start, response.status());
        }
        JsonNode root = readJson(response.body());
        if (root == null || !root.has("choices") || !root.get("choices").isArray()
                || root.get("choices").isEmpty()) {
            return testFailure("接口响应中没有 choices，请检查模型名称与兼容协议", start, response.status());
        }
        return testSuccess("问答模型可用，已成功返回测试回答", start, response.status());
    }

    private ApiTestResultVO testMap(String provider, String baseUrl, String apiKey,
                                    int timeout, long start) throws Exception {
        String root = isBlank(baseUrl) ? TENCENT_BASE_URL : baseUrl;
        if (isBlank(apiKey)) {
            return testFailure("请填写地图 Key（AK）", start, null);
        }
        String url = root + "/ws/geocoder/v1/?location=39.984154,116.307490&key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        HttpResult response = send("GET", url, Map.of(), null, timeout);

        if (!is2xx(response.status())) {
            return testFailure("HTTP " + response.status() + "：" + snippet(response.body()), start, response.status());
        }
        JsonNode node = readJson(response.body());
        if (node == null || !node.has("status")) {
            return testFailure("地图接口响应无法解析，请确认服务商协议", start, response.status());
        }
        int status = node.get("status").asInt(-1);
        if (status == 0) {
            return testSuccess("地图服务可用，Key 校验通过", start, response.status());
        }
        String message = node.has("message") ? node.get("message").asText() : "未知错误";
        // 腾讯 JS API Key 默认只用于浏览器端；未开通 WebServiceAPI 时服务端校验返回 199，
        // 但前台地图仍可正常加载，因此按“Key 有效”提示，而不是误报失败。
        if (status == 199) {
            return testSuccess("地图 Key 有效（未开通 WebServiceAPI，前台 JS 地图仍可正常使用）",
                    start, response.status());
        }
        return testFailure("地图 Key 校验失败（status=" + status + "）：" + message,
                start, response.status());
    }

    private ApiTestResultVO testOther(String baseUrl, String apiKey,
                                      int timeout, long start) throws Exception {
        HttpResult response = send("GET", baseUrl, authHeaders(apiKey), null, timeout);
        if (is2xx(response.status())) {
            return testSuccess("接口可访问（HTTP " + response.status() + "）", start, response.status());
        }
        return testFailure("HTTP " + response.status() + "：" + snippet(response.body()), start, response.status());
    }

    // ==================== HTTP / JSON 工具 ====================

    private HttpResult send(String method, String url, Map<String, String> headers,
                            String body, int timeoutSeconds) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(5, timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", "jqgg-api-manager/1.0");
        headers.forEach(builder::header);
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), response.body());
    }

    private Map<String, String> authHeaders(String apiKey) {
        if (isBlank(apiKey)) {
            return Map.of();
        }
        return Map.of("Authorization", "Bearer " + apiKey);
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("测试参数序列化失败", e);
        }
    }

    private JsonNode readJson(String body) {
        if (isBlank(body)) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean is2xx(int status) {
        return status >= 200 && status < 300;
    }

    private ApiTestResultVO testSuccess(String message, long start, Integer status) {
        return new ApiTestResultVO(true, message, elapsedMs(start), status);
    }

    private ApiTestResultVO testFailure(String message, long start, Integer status) {
        return new ApiTestResultVO(false, message, elapsedMs(start), status);
    }

    private long elapsedMs(long start) {
        return Math.max(0, (System.nanoTime() - start) / 1_000_000);
    }

    private String describeFailure(Exception e, int timeoutSeconds) {
        if (e instanceof HttpConnectTimeoutException) {
            return "连接超时：目标服务在限定时间内未建立连接";
        }
        if (e instanceof HttpTimeoutException) {
            return "请求超时：目标服务超过 " + timeoutSeconds + " 秒未响应";
        }
        if (e instanceof java.net.ConnectException) {
            return "连接失败：目标服务未启动或端口不可达";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "域名解析失败：请检查接口地址";
        }
        if (e instanceof IllegalArgumentException) {
            return "接口地址格式不正确，请使用 http:// 或 https:// 开头";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private String snippet(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replaceAll("\\s+", " ").trim();
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    // ==================== 表单 → 实体 ====================

    /**
     * 把表单写入实体并做业务校验。
     *
     * @param creating true 为新增（apiKey 直接取表单值）；false 为编辑（空 Key 保持原值）
     */
    private void applyForm(ApiConfig config, ApiConfigForm form, boolean creating) {
        ApiType type = parseType(form.type());
        String provider = normalizeProvider(type, form.provider());
        String name = form.name() == null ? "" : form.name().trim();
        if (name.isEmpty()) {
            throw new BadRequestException("请填写配置名称");
        }

        String baseUrl = normalizeBaseUrl(form.baseUrl());
        if (type == ApiType.MAP && isBlank(baseUrl)) {
            baseUrl = TENCENT_BASE_URL;
        }
        if (type != ApiType.MAP && isBlank(baseUrl)) {
            throw new BadRequestException("请填写接口地址");
        }
        validateUrl(baseUrl);

        String model = trimToNull(form.model());
        if ((type == ApiType.EMBEDDING || type == ApiType.LLM) && model == null) {
            throw new BadRequestException("请填写模型名称");
        }
        // 向量模型名称会写入 billboard_knowledge.model（VARCHAR(100)）
        if (model != null && model.length() > 100) {
            throw new BadRequestException("模型名称不能超过 100 个字符");
        }

        config.setName(name);
        config.setType(type);
        config.setProvider(provider);
        config.setBaseUrl(baseUrl);
        config.setModel(model);
        config.setEnabled(form.enabled() == null || form.enabled());
        config.setPreferred(Boolean.TRUE.equals(form.isDefault()));
        config.setRemark(trimToNull(form.remark()));
        config.setTimeoutSeconds(form.timeoutSeconds() == null ? 60 : form.timeoutSeconds());

        if (type == ApiType.LLM) {
            config.setMaxTokens(form.maxTokens() == null ? 2000 : form.maxTokens());
            config.setTemperature(form.temperature() == null ? 0.2 : form.temperature());
        } else {
            config.setMaxTokens(null);
            config.setTemperature(null);
        }

        if (creating) {
            config.setApiKey(trimToNull(form.apiKey()));
        } else if (Boolean.TRUE.equals(form.clearApiKey())) {
            config.setApiKey(null);
        } else {
            String incoming = trimToNull(form.apiKey());
            if (incoming != null) {
                config.setApiKey(incoming);
            }
        }

        if (config.isPreferred()) {
            config.setEnabled(true);
        }
    }

    private ApiType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("请选择接口类型");
        }
        try {
            return ApiType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("接口类型不合法：" + raw);
        }
    }

    private String normalizeProvider(ApiType type, String raw) {
        String provider = raw == null || raw.isBlank()
                ? DEFAULT_PROVIDERS.get(type)
                : raw.trim().toUpperCase(Locale.ROOT);
        Set<String> allowed = PROVIDERS.get(type);
        if (!allowed.contains(provider)) {
            throw new BadRequestException("接口类型与协议不匹配：" + provider);
        }
        return provider;
    }

    private String normalizeBaseUrl(String raw) {
        String url = trimToNull(raw);
        if (url == null) {
            return null;
        }
        return url.replaceAll("/+$", "");
    }

    private void validateUrl(String url) {
        if (url == null) {
            return;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new BadRequestException("接口地址格式不正确，请使用 http:// 或 https:// 开头的完整地址");
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("接口地址格式不正确，请使用 http:// 或 https:// 开头的完整地址");
        }
    }

    // ==================== 默认配置维护 ====================

    private void clearPreferred(ApiType type, Long excludeId) {
        List<ApiConfig> rows = repository.findByTypeOrdered(type);
        List<ApiConfig> changed = new ArrayList<>();
        for (ApiConfig row : rows) {
            if (row.isPreferred() && (excludeId == null || !excludeId.equals(row.getId()))) {
                row.setPreferred(false);
                changed.add(row);
            }
        }
        if (!changed.isEmpty()) {
            repository.saveAll(changed);
        }
    }

    /** 保证每个有启用配置的类型至少有一条默认项。 */
    private void ensureDefault(ApiType type) {
        List<ApiConfig> rows = repository.findByTypeOrdered(type);
        if (rows.stream().anyMatch(c -> c.isPreferred() && c.isEnabled())) {
            return;
        }
        for (ApiConfig row : rows) {
            if (row.isEnabled()) {
                row.setPreferred(true);
                repository.save(row);
                return;
            }
        }
        // 没有启用项时，清理残留的“默认”标记（停用项不应继续显示为默认）
        List<ApiConfig> changed = rows.stream()
                .filter(ApiConfig::isPreferred)
                .peek(row -> row.setPreferred(false))
                .toList();
        if (!changed.isEmpty()) {
            repository.saveAll(changed);
        }
    }

    private Map<ApiType, Long> effectiveIds() {
        Map<ApiType, Long> ids = new EnumMap<>(ApiType.class);
        for (ApiType type : ApiType.values()) {
            ResolvedApi resolved = resolve(type);
            if (resolved != null && resolved.fromDatabase()) {
                ids.put(type, resolved.id());
            }
        }
        return ids;
    }

    private Long effectiveId(ApiType type) {
        return effectiveIds().get(type);
    }

    private ApiConfig load(Long id) {
        try {
            return repository.findById(id)
                    .orElseThrow(() -> new NotFoundException("API 配置不存在"));
        } catch (DataAccessException e) {
            throw databaseUnavailable(e);
        }
    }

    // ==================== 实体 ↔ DTO ====================

    private ApiConfigVO toVO(ApiConfig config, Long effectiveId) {
        String mask = maskKey(config.getApiKey());
        return new ApiConfigVO(
                config.getId(),
                config.getName(),
                config.getType().name(),
                config.getType().getLabel(),
                config.getProvider(),
                PROVIDER_LABELS.getOrDefault(config.getProvider(), config.getProvider()),
                config.getBaseUrl(),
                config.getModel(),
                mask,
                !isBlank(config.getApiKey()),
                config.getMaxTokens(),
                config.getTemperature(),
                config.getTimeoutSeconds(),
                config.isEnabled(),
                config.isPreferred(),
                effectiveId != null && effectiveId.equals(config.getId()),
                config.getRemark(),
                config.getCreatedAt(),
                config.getUpdatedAt());
    }

    private ResolvedApi toResolved(ApiConfig config) {
        int timeout = config.getTimeoutSeconds() == null ? 60 : config.getTimeoutSeconds();
        return new ResolvedApi(
                config.getId(),
                config.getName(),
                config.getType(),
                config.getProvider(),
                config.getBaseUrl(),
                config.getApiKey(),
                config.getModel(),
                config.getMaxTokens(),
                config.getTemperature(),
                timeout,
                true);
    }

    /** 没有任何数据库配置时的 application.yml 兜底配置。 */
    private ResolvedApi fallback(ApiType type) {
        return switch (type) {
            case EMBEDDING -> {
                AppProperties.Embedding embedding = appProperties.getRag().getEmbedding();
                yield new ResolvedApi(null, "application.yml 向量模型", type, "OLLAMA",
                        embedding.getBaseUrl(), null, embedding.getModel(), null, null,
                        embedding.getTimeoutSeconds(), false);
            }
            case LLM -> {
                AppProperties.Llm llm = appProperties.getRag().getLlm();
                AppProperties.Llm.ModelProfile profile = preferredYmlProfile(llm);
                String model = profile != null && !isBlank(profile.getModel())
                        ? profile.getModel() : llm.getModel();
                int maxTokens = profile != null && profile.getMaxTokens() != null && profile.getMaxTokens() > 0
                        ? profile.getMaxTokens() : llm.getMaxTokens();
                double temperature = profile != null && profile.getTemperature() != null
                        ? profile.getTemperature() : 0.2;
                yield new ResolvedApi(null, "application.yml 问答模型", type, "OPENAI",
                        llm.getBaseUrl(), llm.getApiKey(), model, maxTokens, temperature,
                        llm.getTimeoutSeconds(), false);
            }
            case MAP -> new ResolvedApi(null, "application.yml 地图 Key", type, "TENCENT",
                    TENCENT_BASE_URL, appProperties.getMap().getAk(), null, null, null, 5, false);
            case OTHER -> null;
        };
    }

    private AppProperties.Llm.ModelProfile preferredYmlProfile(AppProperties.Llm llm) {
        List<AppProperties.Llm.ModelProfile> profiles = llm.getModels();
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }
        for (AppProperties.Llm.ModelProfile profile : profiles) {
            if (equalsIgnoreCase(profile.getId(), llm.getModel())
                    || equalsIgnoreCase(profile.getModel(), llm.getModel())) {
                return profile;
            }
        }
        return profiles.get(0);
    }

    // ==================== 其他工具 ====================

    private RuntimeException databaseUnavailable(DataAccessException e) {
        log.warn("api_config 表不可用：{}", e.getMessage());
        return new BadRequestException("API 配置表不可用，请先执行 sql/init.sql 创建 api_config 表");
    }

    private void warnDatabaseUnavailable(DataAccessException e) {
        if (databaseWarned.compareAndSet(false, true)) {
            log.warn("api_config 表不可用，已回退 application.yml 配置；"
                    + "请执行 sql/init.sql 创建 api_config 表（{}）", e.getMessage());
        }
    }

    private String maskKey(String apiKey) {
        if (isBlank(apiKey)) {
            return "";
        }
        String key = apiKey.trim();
        if (key.length() <= 12) {
            return "****";
        }
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 一次 HTTP 调用的响应。 */
    private record HttpResult(int status, String body) {
    }

    /**
     * 已解析的运行时接口配置。
     *
     * @param fromDatabase true 表示来自后台管理，false 表示 application.yml 兜底
     */
    public record ResolvedApi(Long id,
                              String name,
                              ApiType type,
                              String provider,
                              String baseUrl,
                              String apiKey,
                              String model,
                              Integer maxTokens,
                              Double temperature,
                              int timeoutSeconds,
                              boolean fromDatabase) {
    }
}
