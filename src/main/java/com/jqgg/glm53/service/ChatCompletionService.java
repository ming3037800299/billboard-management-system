package com.jqgg.glm53.service;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.dto.KnowledgeModelVO;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.exception.RagUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 云端问答模型客户端（OpenAI 兼容 /chat/completions 接口）。
 *
 * <p>模型线路从后台「API 管理」实时读取：每条启用的检索生成配置代表一个可切换模型，
 * 默认项优先；没有数据库配置时回退 application.yml。支持普通回答与 SSE 流式回答。</p>
 */
@Service
public class ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);

    /** 后台管理配置的模型 ID 前缀，与 yml 模型标识区分 */
    private static final String DB_MODEL_PREFIX = "api-";

    private final AppProperties.Llm config;
    private final ApiConfigService apiConfigService;
    private final ObjectMapper objectMapper;

    public ChatCompletionService(AppProperties appProperties,
                                 ApiConfigService apiConfigService,
                                 ObjectMapper objectMapper) {
        this.config = appProperties.getRag().getLlm();
        this.apiConfigService = apiConfigService;
        this.objectMapper = objectMapper;
    }

    /** 是否已配置可用的问答模型（后台配置不强制 Key，兼容本地模型服务）。 */
    public boolean isConfigured() {
        ResolvedModel model = defaultModel();
        return configuredFor(model);
    }

    /** 默认问答模型名称（用于后台状态展示）。 */
    public String model() {
        return defaultModel().model();
    }

    /** 前台可切换的模型列表：后台启用的配置优先，否则使用 application.yml。 */
    public List<KnowledgeModelVO> availableModels() {
        List<ApiConfigService.ResolvedApi> dbModels = apiConfigService.enabledLlmApis();
        if (!dbModels.isEmpty()) {
            String defaultId = dbModelId(dbModels.get(0));
            List<KnowledgeModelVO> options = new ArrayList<>(dbModels.size());
            for (ApiConfigService.ResolvedApi api : dbModels) {
                String id = dbModelId(api);
                options.add(new KnowledgeModelVO(id, modelLabel(api), id.equals(defaultId)));
            }
            return options;
        }

        List<AppProperties.Llm.ModelProfile> configured = config.getModels();
        if (configured == null || configured.isEmpty()) {
            return List.of(new KnowledgeModelVO(config.getModel(), config.getModel(), true));
        }
        String defaultId = defaultModel().id();
        List<KnowledgeModelVO> options = new ArrayList<>();
        for (AppProperties.Llm.ModelProfile profile : configured) {
            ResolvedModel resolved = resolveProfile(profile);
            options.add(new KnowledgeModelVO(
                    resolved.id(),
                    resolved.label(),
                    resolved.id().equalsIgnoreCase(defaultId)));
        }
        return options;
    }

    /** 解析前端传入的模型标识；为空时返回默认模型。 */
    public ResolvedModel resolveModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return defaultModel();
        }
        String requested = modelId.trim();
        List<ApiConfigService.ResolvedApi> dbModels = apiConfigService.enabledLlmApis();
        if (!dbModels.isEmpty()) {
            if (requested.startsWith(DB_MODEL_PREFIX)) {
                Long id = parseDbModelId(requested);
                ApiConfigService.ResolvedApi api = apiConfigService.findEnabledLlm(id);
                if (api == null) {
                    throw new BadRequestException("模型配置不存在或已停用：" + requested);
                }
                return toResolvedModel(api);
            }
            for (ApiConfigService.ResolvedApi api : dbModels) {
                if (equalsIgnoreCase(api.model(), requested)
                        || equalsIgnoreCase(api.name(), requested)
                        || dbModelId(api).equalsIgnoreCase(requested)) {
                    return toResolvedModel(api);
                }
            }
            throw new BadRequestException("不支持的模型：" + modelId);
        }

        List<AppProperties.Llm.ModelProfile> configured = config.getModels();
        if (configured == null || configured.isEmpty()) {
            if (requested.equalsIgnoreCase(config.getModel())) {
                return defaultModel();
            }
            throw new BadRequestException("不支持的模型：" + modelId);
        }
        for (AppProperties.Llm.ModelProfile profile : configured) {
            ResolvedModel resolved = resolveProfile(profile);
            if (resolved.id().equalsIgnoreCase(requested)
                    || resolved.model().equalsIgnoreCase(requested)) {
                return resolved;
            }
        }
        throw new BadRequestException("不支持的模型：" + modelId);
    }

    public ResolvedModel defaultModel() {
        List<ApiConfigService.ResolvedApi> dbModels = apiConfigService.enabledLlmApis();
        if (!dbModels.isEmpty()) {
            return toResolvedModel(dbModels.get(0));
        }
        return ymlDefaultModel();
    }

    private ResolvedModel ymlDefaultModel() {
        List<AppProperties.Llm.ModelProfile> configured = config.getModels();
        if (configured == null || configured.isEmpty()) {
            return new ResolvedModel(
                    config.getModel(), config.getModel(), config.getModel(),
                    config.getMaxTokens(), 0.2,
                    trimTrailingSlash(config.getBaseUrl()), config.getApiKey(),
                    config.getConnectTimeoutSeconds(), config.getTimeoutSeconds(), false);
        }
        AppProperties.Llm.ModelProfile preferred = configured.get(0);
        for (AppProperties.Llm.ModelProfile profile : configured) {
            ResolvedModel resolved = resolveProfile(profile);
            if (resolved.id().equalsIgnoreCase(config.getModel())
                    || resolved.model().equalsIgnoreCase(config.getModel())) {
                preferred = profile;
                break;
            }
        }
        return resolveProfile(preferred);
    }

    private ResolvedModel resolveProfile(AppProperties.Llm.ModelProfile profile) {
        String id = firstNonBlank(profile.getId(), profile.getModel(), config.getModel());
        String model = firstNonBlank(profile.getModel(), config.getModel());
        String label = firstNonBlank(profile.getLabel(), id);
        int maxTokens = profile.getMaxTokens() != null && profile.getMaxTokens() > 0
                ? profile.getMaxTokens() : config.getMaxTokens();
        double temperature = profile.getTemperature() != null
                ? profile.getTemperature() : 0.2;
        return new ResolvedModel(id, label, model, maxTokens, temperature,
                trimTrailingSlash(config.getBaseUrl()), config.getApiKey(),
                config.getConnectTimeoutSeconds(), config.getTimeoutSeconds(), false);
    }

    private ResolvedModel toResolvedModel(ApiConfigService.ResolvedApi api) {
        int maxTokens = api.maxTokens() != null && api.maxTokens() > 0
                ? api.maxTokens() : config.getMaxTokens();
        double temperature = api.temperature() != null ? api.temperature() : 0.2;
        return new ResolvedModel(
                dbModelId(api),
                modelLabel(api),
                api.model(),
                maxTokens,
                temperature,
                trimTrailingSlash(api.baseUrl()),
                api.apiKey(),
                config.getConnectTimeoutSeconds(),
                Math.max(5, api.timeoutSeconds()),
                true);
    }

    private String dbModelId(ApiConfigService.ResolvedApi api) {
        return DB_MODEL_PREFIX + api.id();
    }

    private Long parseDbModelId(String modelId) {
        try {
            return Long.parseLong(modelId.substring(DB_MODEL_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new BadRequestException("不支持的模型：" + modelId);
        }
    }

    private String modelLabel(ApiConfigService.ResolvedApi api) {
        String name = api.name();
        if (name == null || name.isBlank()) {
            return api.model();
        }
        if (equalsIgnoreCase(name, api.model())) {
            return name;
        }
        return name + "（" + api.model() + "）";
    }

    private boolean configuredFor(ResolvedModel model) {
        if (model == null || isBlank(model.baseUrl()) || isBlank(model.model())) {
            return false;
        }
        if (!isBlank(model.apiKey())) {
            return true;
        }
        // 后台配置对本地模型服务允许不填 Key；远程线路仍要求 Key，避免把 401 当成“已配置”
        return model.fromDatabase() && isLocalEndpoint(model.baseUrl());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 调用云端模型生成回答。
     * 未配置 Key 或调用失败时抛出异常，由 KnowledgeService 降级为检索结果。
     */
    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, defaultModel());
    }

    public String complete(String systemPrompt, String userPrompt, ResolvedModel model) {
        return complete(systemPrompt, userPrompt, model, model.maxTokens());
    }

    public String complete(String systemPrompt, String userPrompt,
                           ResolvedModel model, int maxTokens) {
        if (!configuredFor(model)) {
            throw new RagUnavailableException("未配置云端问答模型 API Key");
        }
        try {
            RestClient.RequestBodySpec request = client(model).post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON);
            if (!isBlank(model.apiKey())) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + model.apiKey().trim());
            }
            ChatResponse response = request
                    .body(Map.of(
                            "model", model.model(),
                            "messages", List.of(
                                    Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", userPrompt)),
                            "temperature", model.temperature(),
                            "max_tokens", Math.max(1, maxTokens),
                            "stream", false))
                    .retrieve()
                    .body(ChatResponse.class);

            String content = contentOf(response);
            if (content == null || content.isBlank()) {
                throw new RagUnavailableException("云端问答模型返回空内容");
            }
            return content.trim();
        } catch (RagUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new RagUnavailableException("云端问答模型调用失败，请检查 API Key、网络和模型名称", e);
        } catch (Exception e) {
            throw new RagUnavailableException("云端问答模型调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 流式调用云端模型。
     *
     * @param onDelta 每收到一段增量文本调用一次；返回 false 表示调用方要求停止（如客户端断开）
     * @return true 表示正常读到流结束；false 表示被 onDelta 中止
     */
    public boolean stream(String systemPrompt, String userPrompt, Predicate<String> onDelta) {
        return stream(systemPrompt, userPrompt, defaultModel(), onDelta);
    }

    public boolean stream(String systemPrompt, String userPrompt,
                          ResolvedModel model, Predicate<String> onDelta) {
        Map<String, Object> system = new HashMap<>();
        system.put("role", "system");
        system.put("content", systemPrompt);
        Map<String, Object> user = new HashMap<>();
        user.put("role", "user");
        user.put("content", userPrompt);
        return streamMessages(model, List.of(system, user), onDelta);
    }

    /**
     * 视觉模型流式调用：在 user content 中附带 data URL 图片。
     * 模型不支持图片时会抛出异常，由图片问答降级为本地视觉特征分析。
     */
    public boolean streamVision(String systemPrompt, String userPrompt, String imageDataUrl,
                                ResolvedModel model, Predicate<String> onDelta) {
        Map<String, Object> system = new HashMap<>();
        system.put("role", "system");
        system.put("content", systemPrompt);
        Map<String, Object> user = new HashMap<>();
        user.put("role", "user");
        user.put("content", List.of(
                Map.of("type", "text", "text", userPrompt),
                Map.of("type", "image_url", "image_url", Map.of("url", imageDataUrl))));
        return streamMessages(model, List.of(system, user), onDelta);
    }

    private boolean streamMessages(ResolvedModel model,
                                   List<Map<String, Object>> messages,
                                   Predicate<String> onDelta) {
        if (!configuredFor(model)) {
            throw new RagUnavailableException("未配置云端问答模型 API Key");
        }
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model.model(),
                    "messages", messages,
                    "temperature", model.temperature(),
                    "max_tokens", model.maxTokens(),
                    "stream", true));

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, model.connectTimeoutSeconds())))
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(model.baseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(Math.max(5, model.timeoutSeconds())))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "text/event-stream");
            if (!isBlank(model.apiKey())) {
                builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + model.apiKey().trim());
            }
            HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error;
                try (InputStream errorBody = response.body()) {
                    error = new String(errorBody.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new RagUnavailableException("云端问答模型调用失败（HTTP "
                        + response.statusCode() + "）：" + truncate(error, 300));
            }

            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(Locale.ROOT);
            try (InputStream input = response.body()) {
                if (contentType.contains("text/event-stream")) {
                    return parseSse(input, onDelta);
                }
                // 部分兼容服务即使传了 stream=true，也会返回整段 JSON。
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                String content = contentOf(objectMapper.readValue(json, ChatResponse.class));
                if (content == null || content.isBlank()) {
                    throw new RagUnavailableException("云端问答模型返回空内容");
                }
                return onDelta.test(content.trim());
            }
        } catch (RagUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagUnavailableException("云端问答模型流式调用被中断", e);
        } catch (Exception e) {
            throw new RagUnavailableException("云端问答模型流式调用失败：" + e.getMessage(), e);
        }
    }

    private RestClient client(ResolvedModel model) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, model.connectTimeoutSeconds())))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(Math.max(5, model.timeoutSeconds())));
        return RestClient.builder()
                .baseUrl(trimTrailingSlash(model.baseUrl()))
                .requestFactory(factory)
                .build();
    }

    /** 读取 OpenAI 兼容 SSE：按空行分隔事件，data 行拼接后 JSON 解析。 */
    private boolean parseSse(InputStream input, Predicate<String> onDelta) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        String payload = data.toString();
                        data.setLength(0);
                        if ("[DONE]".equals(payload.trim())) {
                            return true;
                        }
                        if (!dispatchSse(payload, onDelta)) {
                            return false;
                        }
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
            if (data.length() > 0) {
                String payload = data.toString();
                if ("[DONE]".equals(payload.trim())) {
                    return true;
                }
                return dispatchSse(payload, onDelta);
            }
            return true;
        }
    }

    /** 解析单个流式片段；客户端要求停止时返回 false。 */
    private boolean dispatchSse(String data, Predicate<String> onDelta) {
        if (data == null || data.isBlank()) {
            return true;
        }
        String content;
        try {
            StreamChunk chunk = objectMapper.readValue(data, StreamChunk.class);
            if (chunk == null || chunk.choices() == null || chunk.choices().isEmpty()) {
                return true;
            }
            StreamChoice choice = chunk.choices().get(0);
            content = choice.delta() != null ? choice.delta().content() : null;
            if (content == null && choice.message() != null) {
                content = choice.message().content();
            }
        } catch (Exception e) {
            log.warn("忽略无法解析的流式响应片段：{}", truncate(data, 200));
            return true;
        }
        return content == null || content.isEmpty() || onDelta.test(content);
    }

    private String contentOf(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        Choice choice = response.choices().get(0);
        return choice.message() == null ? null : choice.message().content();
    }

    private boolean isLocalEndpoint(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "0.0.0.0".equals(host)
                    || "::1".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.deepseek.com/v1";
        }
        return url.replaceAll("/+$", "");
    }

    /**
     * 已解析的模型配置：前端标识、展示名、实际 model、token 上限、温度、
     * 以及该模型对应的接口地址 / Key / 超时（后台多线路时每条配置可不同）。
     */
    public record ResolvedModel(String id, String label, String model,
                                int maxTokens, double temperature,
                                String baseUrl, String apiKey,
                                int connectTimeoutSeconds, int timeoutSeconds,
                                boolean fromDatabase) {
    }

    private record ChatResponse(List<Choice> choices) {
    }

    private record Choice(Message message) {
    }

    private record Message(String content) {
    }

    private record StreamChunk(List<StreamChoice> choices) {
    }

    private record StreamChoice(StreamDelta delta, Message message) {
    }

    private record StreamDelta(String content) {
    }
}
