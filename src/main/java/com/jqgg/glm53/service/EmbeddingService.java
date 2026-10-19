package com.jqgg.glm53.service;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.entity.ApiType;
import com.jqgg.glm53.exception.RagUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量模型客户端。
 *
 * <p>支持两种协议：
 * <ul>
 *   <li>OLLAMA：Ollama 原生 /api/embed（本地 bge-m3 默认）；</li>
 *   <li>OPENAI：OpenAI 兼容 /embeddings（百炼、硅基流动等）。</li>
 * </ul>
 * 地址、模型与 Key 从后台「API 管理」实时读取；没有启用配置时回退 application.yml。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final ApiConfigService apiConfigService;
    private final AppProperties appProperties;

    public EmbeddingService(ApiConfigService apiConfigService, AppProperties appProperties) {
        this.apiConfigService = apiConfigService;
        this.appProperties = appProperties;
    }

    /** 当前生效的向量模型名称。 */
    public String model() {
        ApiConfigService.ResolvedApi config = config();
        return config == null || config.model() == null ? "" : config.model();
    }

    /**
     * 向量模型签名：服务商 + 地址 + 模型。
     * 任一变化都会让已有知识库索引失效并触发重建。
     */
    public String signature() {
        ApiConfigService.ResolvedApi config = config();
        if (config == null) {
            return "unconfigured";
        }
        return config.provider() + "|" + nullToEmpty(config.baseUrl()) + "|" + nullToEmpty(config.model());
    }

    /** 当前向量服务是否在线且模型可用（知识库后台状态展示）。 */
    public boolean isAvailable() {
        ApiConfigService.ResolvedApi config = config();
        if (config == null || isBlank(config.baseUrl()) || isBlank(config.model())) {
            return false;
        }
        try {
            if (isOpenAi(config)) {
                try {
                    RestClient client = client(config, 5);
                    client.get().uri("/models")
                            .headers(headers -> applyAuth(headers, config.apiKey()))
                            .retrieve()
                            .toBodilessEntity();
                    return true;
                } catch (Exception ignored) {
                    // 部分兼容服务不实现 /models，回退到一次最小向量化探测
                    embedAll(List.of("ping"));
                    return true;
                }
            }
            RestClient client = client(config, 5);
            TagsResponse response = client.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(TagsResponse.class);
            if (response == null || response.models() == null) {
                return false;
            }
            String expected = config.model();
            return response.models().stream()
                    .map(TagModel::name)
                    .filter(name -> name != null)
                    .anyMatch(name -> name.equals(expected) || name.startsWith(expected + ":"));
        } catch (Exception e) {
            return false;
        }
    }

    /** 单条文本向量化。 */
    public List<Double> embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    /**
     * 批量向量化，返回顺序与入参一致。
     * 失败时抛出 {@link RagUnavailableException}，由上层给出友好的 503 提示。
     */
    public List<List<Double>> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        ApiConfigService.ResolvedApi config = config();
        if (config == null || isBlank(config.baseUrl()) || isBlank(config.model())) {
            throw new RagUnavailableException("未配置向量模型接口，请在后台「API 管理」中添加");
        }
        try {
            return isOpenAi(config)
                    ? openAiEmbeddings(config, texts)
                    : ollamaEmbeddings(config, texts);
        } catch (RagUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("向量服务请求失败（{} /{}）：{}", config.baseUrl(), config.provider(), e.toString(), e);
            throw new RagUnavailableException(
                    "向量模型不可用：请检查「API 管理」中的地址（" + config.baseUrl()
                            + "）与模型 " + config.model() + " 是否可用", e);
        } catch (Exception e) {
            log.warn("向量化失败：{}", e.toString(), e);
            throw new RagUnavailableException("向量化失败：" + e.getMessage(), e);
        }
    }

    private List<List<Double>> ollamaEmbeddings(ApiConfigService.ResolvedApi config,
                                                 List<String> texts) {
        EmbedResponse response = client(config).post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", config.model(), "input", texts))
                .retrieve()
                .body(EmbedResponse.class);
        if (response == null || response.embeddings() == null
                || response.embeddings().size() != texts.size()) {
            throw new RagUnavailableException("向量模型返回结果不完整，请稍后重试");
        }
        return response.embeddings();
    }

    private List<List<Double>> openAiEmbeddings(ApiConfigService.ResolvedApi config,
                                                List<String> texts) {
        RestClient client = client(config);
        OpenAiEmbedResponse response = client.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, config.apiKey()))
                .body(Map.of("model", config.model(), "input", texts))
                .retrieve()
                .body(OpenAiEmbedResponse.class);
        if (response == null || response.data() == null || response.data().size() != texts.size()) {
            throw new RagUnavailableException("向量模型返回结果不完整，请稍后重试");
        }
        List<List<Double>> vectors = new ArrayList<>(response.data().size());
        for (OpenAiItem item : response.data()) {
            if (item == null || item.embedding() == null) {
                throw new RagUnavailableException("向量模型返回结果不完整，请稍后重试");
            }
            vectors.add(item.embedding());
        }
        return vectors;
    }

    private ApiConfigService.ResolvedApi config() {
        return apiConfigService.resolve(ApiType.EMBEDDING);
    }

    private boolean isOpenAi(ApiConfigService.ResolvedApi config) {
        return "OPENAI".equalsIgnoreCase(config.provider());
    }

    /** 构建请求客户端；readTimeoutOverride 用于状态探测，避免长时间等待。 */
    private RestClient client(ApiConfigService.ResolvedApi config, int readTimeoutOverride) {
        int connect = Math.max(1, appProperties.getRag().getEmbedding().getConnectTimeoutSeconds());
        int read = readTimeoutOverride > 0
                ? readTimeoutOverride
                : Math.max(5, config.timeoutSeconds());
        return buildClient(config.baseUrl(), connect, read);
    }

    private RestClient client(ApiConfigService.ResolvedApi config) {
        return client(config, 0);
    }

    private RestClient buildClient(String baseUrl, int connectSeconds, int readSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectSeconds)))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(Math.max(2, readSeconds)));
        return RestClient.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .requestFactory(factory)
                .build();
    }

    private void applyAuth(HttpHeaders headers, String apiKey) {
        if (!isBlank(apiKey)) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }
    }

    private String trimTrailingSlash(String url) {
        if (isBlank(url)) {
            return "http://127.0.0.1:11434";
        }
        return url.replaceAll("/+$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Ollama /api/tags 响应。 */
    private record TagsResponse(List<TagModel> models) {
    }

    private record TagModel(String name) {
    }

    /** Ollama /api/embed 响应。 */
    private record EmbedResponse(List<List<Double>> embeddings) {
    }

    /** OpenAI 兼容 /embeddings 响应。 */
    private record OpenAiEmbedResponse(List<OpenAiItem> data) {
    }

    private record OpenAiItem(Integer index, List<Double> embedding) {
    }
}
