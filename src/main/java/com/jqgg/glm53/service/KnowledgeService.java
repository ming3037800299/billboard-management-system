package com.jqgg.glm53.service;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.dto.ChatTurn;
import com.jqgg.glm53.dto.GuessYouLikeVO;
import com.jqgg.glm53.dto.KnowledgeAnswerVO;
import com.jqgg.glm53.dto.KnowledgeDocumentVO;
import com.jqgg.glm53.dto.KnowledgeModelVO;
import com.jqgg.glm53.dto.KnowledgeSourceVO;
import com.jqgg.glm53.dto.KnowledgeStatusVO;
import com.jqgg.glm53.entity.Billboard;
import com.jqgg.glm53.entity.BillboardStatus;
import com.jqgg.glm53.entity.KnowledgeDocument;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.repository.BillboardRepository;
import com.jqgg.glm53.repository.KnowledgeDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 广告牌知识库服务：
 *   1. 把每条广告牌拼接成结构化知识文本，调用本地 bge-m3 生成向量；
 *   2. 向量存 MySQL，检索时在应用内做余弦相似度排序；
 *   3. 命中结果交给云端大模型生成答案；未配置 API Key 时降级为检索结果摘要；
 *   4. 广告牌新增 / 编辑 / 删除 / 恢复时自动同步索引。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 批量向量化时每批文档数 */
    private static final int BATCH_SIZE = 8;

    /** 单次问答最多返回的参考广告牌数（纯语义检索） */
    private static final int MAX_TOP_K = 10;

    /** 结构化筛选时最多送入大模型的资料数（覆盖全量结果，避免只看到相似度前几条） */
    private static final int MAX_CONTEXT = 20;

    private static final String READY = "READY";
    private static final String PENDING = "PENDING";
    private static final String ERROR = "ERROR";

    private static final String SYSTEM_PROMPT = """
            你是“小金桥”，公路广告牌租赁系统的高级公路广告牌营销推荐员。
            你的职责是理解客户的投放预算、路段、受众和品牌调性，给出专业、可执行的广告牌选牌建议。
            你的语气专业、热情、简洁，像一位资深营销顾问；可以自然使用“我建议”“从投放角度看”等表达，
            但不要虚构数据，也不要每段重复自我介绍。
            请严格依据用户问题下方提供的【广告牌资料】回答，不要编造资料中没有的信息。
            如果资料不足以回答，请明确说明“现有资料中没有找到相关信息”，并建议用户换个问法。
            回答使用简体中文，条理清晰；涉及价格时必须注明计费周期；不要输出资料之外的推测。
            如果问题涉及预算、价格高低、租赁状态或需要列出全部符合条件的广告牌，请逐条核对资料中的结构化字段，
            不要只根据检索相似度或前几条资料下结论；资料标注“月均折算”时可用于横向比较，但回答必须保留原始计费周期。
            回答使用 Markdown 组织（小标题、列表、必要时用表格）；条目较多时优先用表格或紧凑列表，
            避免重复冗长描述，确保表格列数一致、加粗标记成对闭合。
            回答尽量简练：先给结论，再给必要依据，不要复述资料全文。除非用户明确要求全部列出或详细对比，
            否则控制在 3-6 个要点或一张精简表格。
            """;

    private static final String IMAGE_SYSTEM_PROMPT = SYSTEM_PROMPT + """
            用户上传了一张图片，你可以直接看到图片内容。
            请先准确识别图片中的全部文字（中文、英文、数字），再描述画面中的主要物体、场景、构图、主色和可见的品牌 / Logo。
            看不清、被遮挡或无法确认的内容必须明确说明，严禁编造图片中不存在的文字、品牌或物体。
            然后结合下方提供的本地视觉相似广告牌资料和用户问题，分析相似性并给出选牌建议。
            回答使用 Markdown 分节；识别文字按需列出，不要重复解释。整体保持简练：
            先给图片识别结论，再用 2-4 个要点给出相似性和选牌建议。
            """;

    private static final String IMAGE_FALLBACK_PROMPT = SYSTEM_PROMPT + """
            用户上传了一张图片，但当前模型没有返回可用的识图结果。
            请仅依据系统提供的本地视觉特征（主色、亮度、构图、画面比例）和广告牌资料分析相似性，
            并明确说明这是基于颜色 / 结构 / 构图的本地比对，不要声称识别了图片中的文字、物体或品牌。
            """;

    private static final String CHAT_SYSTEM_PROMPT = """
            你是“小金桥”，公路广告牌租赁系统的高级公路广告牌营销推荐员。当前用户是在进行普通对话，而不是咨询广告牌选牌 / 租赁业务。
            请自然、简洁地用中文回答；不要主动推荐广告牌，不要罗列广告牌资料，也不要假装查询了知识库。
            可以介绍自己的能力、回答一般知识或进行闲聊；不知道或需要实时信息时直接说明。
            回答通常 2-5 句话，使用 Markdown，不要长篇大论。
            """;

    private static final String IMAGE_CHAT_SYSTEM_PROMPT = """
            你是“小金桥”，公路广告牌租赁系统的高级公路广告牌营销推荐员。
            你可以看到用户上传的图片。请识别图片中的文字（如有）并理解画面，然后自然、简洁地回答用户问题。
            如果用户没有要求推荐广告牌，不要主动推荐广告牌，也不要提及广告牌资料或租赁报价。
            看不清的文字、物体或品牌必须明确说明，不要编造。回答尽量简短，使用 Markdown。
            """;

    private final BillboardRepository billboardRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final EmbeddingService embeddingService;
    private final ChatCompletionService chatService;
    private final ImageSimilarityService imageSimilarityService;
    private final IntentClassifier intentClassifier;
    private final BillboardFieldService billboardFieldService;
    private final AppProperties appProperties;
    private final KnowledgeQueryAnalyzer queryAnalyzer = new KnowledgeQueryAnalyzer();

    public KnowledgeService(BillboardRepository billboardRepository,
                            KnowledgeDocumentRepository documentRepository,
                            EmbeddingService embeddingService,
                            ChatCompletionService chatService,
                            ImageSimilarityService imageSimilarityService,
                            IntentClassifier intentClassifier,
                            BillboardFieldService billboardFieldService,
                            AppProperties appProperties) {
        this.billboardRepository = billboardRepository;
        this.documentRepository = documentRepository;
        this.embeddingService = embeddingService;
        this.chatService = chatService;
        this.imageSimilarityService = imageSimilarityService;
        this.intentClassifier = intentClassifier;
        this.billboardFieldService = billboardFieldService;
        this.appProperties = appProperties;
    }

    public boolean isEnabled() {
        return appProperties.getRag().isEnabled();
    }

    // ==================== 索引同步 ====================

    /**
     * 同步单条广告牌的索引；失败只记录错误状态，不打断广告牌保存。
     */
    public void indexBillboardSafely(Long billboardId) {
        if (!isEnabled() || billboardId == null) {
            return;
        }
        try {
            Billboard b = billboardRepository.findById(billboardId).orElse(null);
            if (b == null || b.isDeleted()) {
                removeSafely(billboardId);
                return;
            }
            indexBillboard(b);
        } catch (Exception e) {
            log.warn("知识库同步失败 billboardId={}：{}", billboardId, e.getMessage());
        }
    }

    /** 异步同步单条索引（广告牌保存请求不等待向量模型）。 */
    @Async("knowledgeExecutor")
    public void indexBillboardSafelyAsync(Long billboardId) {
        if (!isEnabled()) {
            return;
        }
        indexBillboardSafely(billboardId);
    }

    /** 删除广告牌对应知识文档（软删除 / 恢复由调用方处理）。 */
    public void removeSafely(Long billboardId) {
        try {
            if (billboardId != null && documentRepository.existsById(billboardId)) {
                documentRepository.deleteById(billboardId);
            }
        } catch (Exception e) {
            log.warn("删除知识库文档失败 billboardId={}：{}", billboardId, e.getMessage());
        }
    }

    /**
     * 后台 API 管理修改向量模型配置后，异步重建失效索引。
     * 内容与向量签名都未变化的文档会被跳过，不会重复调用模型。
     */
    @Async("knowledgeExecutor")
    public void reindexForConfigChangeAsync() {
        if (!isEnabled()) {
            return;
        }
        try {
            KnowledgeStatusVO status = doRebuild(false);
            log.info("向量模型配置变更，知识库索引已同步：就绪 {} / 总数 {}，失败 {}",
                    status.readyCount(), status.totalBillboards(), status.errorCount());
        } catch (Exception e) {
            log.warn("向量模型配置变更后重建索引失败：{}", e.getMessage());
        }
    }

    /** 启动后异步补齐 / 更新全部索引。 */
    @Async("knowledgeExecutor")
    public void indexAllMissingAsync() {
        if (!isEnabled() || !appProperties.getRag().isAutoIndex()) {
            return;
        }
        try {
            KnowledgeStatusVO status = doRebuild(false);
            log.info("知识库启动索引完成：总数 {}，已就绪 {}，待处理 {}，错误 {}",
                    status.totalBillboards(), status.readyCount(), status.pendingCount(), status.errorCount());
        } catch (Exception e) {
            log.warn("知识库启动索引失败：{}", e.getMessage());
        }
    }

    /** 单条广告牌向量化并落库。 */
    private void indexBillboard(Billboard b) {
        String content = contentOf(b);
        String model = embeddingService.model();
        // 签名包含服务商 / 地址 / 模型：后台切换向量线路后已有向量自动失效并重建
        String hash = hashOf(content, embeddingService.signature());
        KnowledgeDocument doc = documentRepository.findById(b.getId())
                .orElseGet(KnowledgeDocument::new);

        boolean upToDate = doc.getBillboardId() != null
                && READY.equals(doc.getStatus())
                && hash.equals(doc.getContentHash())
                && model.equals(doc.getModel())
                && doc.getEmbedding() != null;
        if (upToDate) {
            return;
        }

        doc.setBillboardId(b.getId());
        doc.setContent(content);
        doc.setContentHash(hash);
        doc.setModel(model);
        try {
            List<Double> vector = embeddingService.embed(content);
            doc.setEmbedding(vectorToJson(vector));
            doc.setDimension(vector.size());
            doc.setStatus(READY);
            doc.setErrorMessage(null);
            doc.setIndexedAt(LocalDateTime.now());
        } catch (Exception e) {
            doc.setEmbedding(null);
            doc.setDimension(null);
            doc.setStatus(ERROR);
            doc.setErrorMessage(truncate(e.getMessage(), 500));
            doc.setIndexedAt(null);
            log.warn("广告牌[{}]向量化失败：{}", b.getId(), e.getMessage());
        }

        // 异步索引期间广告牌可能已被删除或再次修改：
        // 删除则清理文档；已有更新版本则放弃本次旧快照，交给后续同步。
        Billboard latest = billboardRepository.findById(b.getId()).orElse(null);
        if (latest == null || latest.isDeleted()) {
            removeSafely(b.getId());
            return;
        }
        if (b.getUpdatedAt() != null && latest.getUpdatedAt() != null
                && latest.getUpdatedAt().isAfter(b.getUpdatedAt())) {
            return;
        }

        documentRepository.save(doc);
    }

    // ==================== 问答 ====================

    /**
     * 知识库问答（非流式）：向量检索 topK 广告牌 → 云端大模型生成答案。
     * 未配置可用的问答模型时返回检索结果摘要，保证功能仍可用。
     */
    public KnowledgeAnswerVO ask(String question, Integer topKRequest) {
        return ask(question, topKRequest, null, List.of());
    }

    public KnowledgeAnswerVO ask(String question, Integer topKRequest, String modelId) {
        return ask(question, topKRequest, modelId, List.of());
    }

    public KnowledgeAnswerVO ask(String question, Integer topKRequest, String modelId,
                                 List<ChatTurn> history) {
        long start = System.currentTimeMillis();
        ChatCompletionService.ResolvedModel model = chatService.resolveModel(modelId);
        if (intentClassifier.classify(question, history, false) == UserIntent.CHAT) {
            if (!chatService.isConfigured()) {
                return answer("当前未配置可用的对话模型，暂时无法聊天。",
                        List.of(), start, model);
            }
            try {
                String text = chatService.complete(CHAT_SYSTEM_PROMPT,
                        buildChatPrompt(question, history), model);
                return answer(text, List.of(), start, model);
            } catch (Exception e) {
                log.warn("普通对话调用失败：{}", e.getMessage());
                return answer("当前对话模型暂时不可用（" + messageOf(e) + "）。",
                        List.of(), start, model);
            }
        }
        AskContext ctx = prepare(question, topKRequest, history);
        if (ctx.immediateAnswer() != null) {
            return answer(ctx.immediateAnswer(), ctx.sources(), start, model);
        }
        String answer;
        if (chatService.isConfigured()) {
            try {
                answer = chatService.complete(SYSTEM_PROMPT,
                        buildUserPrompt(ctx.question(), ctx.items(), ctx.intent(),
                                ctx.totalMatches(), history, ctx.broadRecommendation()),
                        model);
            } catch (Exception e) {
                log.warn("问答模型调用失败，降级为检索结果：{}", e.getMessage());
                answer = "问答模型暂时不可用（" + e.getMessage() + "），以下为知识库检索结果：\n\n"
                        + fallbackList(ctx.sources());
            }
        } else {
            answer = "未配置可用的问答模型（可在后台「API 管理」中添加），以下为知识库检索结果：\n\n"
                    + fallbackList(ctx.sources());
        }
        return answer(answer, ctx.sources(), start, model);
    }

    /** 检索阶段：解析问题、向量检索 / 结构化筛选，返回可选答案与资料。 */
    private AskContext prepare(String question, Integer topKRequest, List<ChatTurn> history) {
        if (!isEnabled()) {
            throw new BadRequestException("知识库功能未启用");
        }
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            throw new BadRequestException("请输入问题");
        }
        int topK = topKRequest == null ? appProperties.getRag().getTopK() : topKRequest;
        topK = Math.max(1, Math.min(MAX_TOP_K, topK));
        List<KnowledgeDocument> docs = documentRepository.findAll().stream()
                .filter(d -> READY.equals(d.getStatus()) && d.getEmbedding() != null)
                .toList();
        if (docs.isEmpty()) {
            return new AskContext(q, List.of(), List.of(), null, 0,
                    "知识库还没有可用索引，请先在后台知识库页面重建索引。", false);
        }

        Set<Long> billboardIds = docs.stream()
                .map(KnowledgeDocument::getBillboardId)
                .collect(Collectors.toSet());
        Map<Long, Billboard> billboards = billboardRepository.findAllById(billboardIds).stream()
                .filter(b -> !b.isDeleted())
                .collect(Collectors.toMap(Billboard::getId, b -> b));
        String searchQuery = retrievalQuery(q, history, billboards.values());

        // 问题向量化；Ollama 不可用时抛出 503。追问会把上一轮用户问题一起用于检索。
        List<Double> questionVector = embeddingService.embed(searchQuery);

        List<Scored> scored = new ArrayList<>();
        for (KnowledgeDocument doc : docs) {
            Billboard b = billboards.get(doc.getBillboardId());
            if (b == null) {
                continue;
            }
            List<Double> vector = vectorFromJson(doc.getEmbedding());
            if (vector == null || vector.size() != questionVector.size()) {
                continue;
            }
            scored.add(new Scored(b, doc.getContent(), cosine(questionVector, vector)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        // 结构化条件优先：价格 / 状态 / 位置需要全量比较，不能只看向量 topK。
        KnowledgeQueryAnalyzer.QueryIntent intent = queryAnalyzer.analyze(searchQuery, billboards.values());
        StructuredResult selected;
        if (intent.hasFilterOrSort()) {
            selected = structuredCandidates(intent, billboards, scored);
            if (selected.items().isEmpty()) {
                return new AskContext(q, List.of(), List.of(), intent, 0,
                        "按这个条件暂时没找到合适的广告牌。可以放宽价格或状态，或者告诉我想投的路段，我再帮你找。",
                        false);
            }
        } else {
            double minScore = appProperties.getRag().getMinScore();
            List<Scored> semantic = scored.stream()
                    .filter(s -> s.score() >= minScore)
                    // 问题中明确出现公路/地名/设施词时，先做词面过滤，避免语义相近但位置不符的结果。
                    .filter(s -> intent.locationKeyword() == null
                            || KnowledgeQueryAnalyzer.containsLocation(
                                    s.billboard(), intent.locationKeyword()))
                    .limit(topK)
                    .toList();
            if (semantic.isEmpty()) {
                // 宽泛的“推荐几个”没有具体条件时，不直接回“没有找到”，
                // 先挑几条代表性可租点位，让模型做初步推荐并追问偏好。
                if (intent.locationKeyword() == null
                        && isBroadRecommendation(q) && !scored.isEmpty()) {
                    List<Scored> recommendations = broadRecommendations(scored, topK);
                    if (!recommendations.isEmpty()) {
                        return new AskContext(q, recommendations,
                                toSources(recommendations, "RECOMMEND"),
                                intent, recommendations.size(), null, true);
                    }
                }
                return new AskContext(q, List.of(), List.of(), intent, 0,
                        "我在现有资料里暂时没匹配到合适的广告牌。你可以告诉我预算、想投的路段或品牌类型吗？我帮你缩小范围。",
                        false);
            }
            selected = new StructuredResult(semantic, semantic.size());
        }
        List<Scored> top = selected.items();
        List<KnowledgeSourceVO> sources = toSources(top, "TEXT");
        return new AskContext(q, top, sources, intent, selected.total(), null, false);
    }

    private List<KnowledgeSourceVO> toSources(List<Scored> items, String matchType) {
        return items.stream()
                .map(s -> new KnowledgeSourceVO(
                        s.billboard().getId(),
                        s.billboard().getName(),
                        s.billboard().getCode(),
                        s.billboard().getRoad(),
                        s.billboard().getSection(),
                        s.billboard().getPrice(),
                        s.billboard().getPriceUnit(),
                        s.billboard().getStatus().name(),
                        s.billboard().getStatus().label(),
                        Math.round(s.score() * 10000) / 10000.0,
                        matchType))
                .toList();
    }

    private boolean isBroadRecommendation(String question) {
        if (question == null) {
            return false;
        }
        String q = question.trim();
        return q.contains("推荐") || q.contains("挑") || q.contains("几个")
                || q.contains("有什么好") || q.contains("性价比") || q.contains("适合");
    }

    /** 宽泛推荐：优先可租赁、按月均价升序、尽量覆盖不同公路。 */
    private List<Scored> broadRecommendations(List<Scored> scored, int topK) {
        List<Scored> available = scored.stream()
                .filter(s -> s.billboard().getStatus() == BillboardStatus.AVAILABLE)
                .toList();
        List<Scored> pool = available.isEmpty() ? new ArrayList<>(scored) : new ArrayList<>(available);
        Comparator<Scored> byPrice = Comparator.comparing(
                (Scored s) -> KnowledgeQueryAnalyzer.monthlyPrice(s.billboard()),
                Comparator.nullsLast(Comparator.<Double>naturalOrder()));
        pool.sort(byPrice.thenComparing(Comparator.comparingDouble(Scored::score).reversed()));

        List<Scored> picked = new ArrayList<>();
        Set<Long> pickedIds = new HashSet<>();
        Set<String> roads = new HashSet<>();
        for (Scored s : pool) {
            String road = s.billboard().getRoad() == null ? "" : s.billboard().getRoad();
            if (roads.add(road) && pickedIds.add(s.billboard().getId())) {
                picked.add(s);
            }
            if (picked.size() >= topK) {
                return picked;
            }
        }
        for (Scored s : pool) {
            if (pickedIds.add(s.billboard().getId())) {
                picked.add(s);
            }
            if (picked.size() >= topK) {
                break;
            }
        }
        return picked;
    }

    /** 前台可切换的模型列表。 */
    public List<KnowledgeModelVO> availableModels() {
        return chatService.availableModels();
    }

    /** 首次进入时的短提问建议：根据知识库实际公路 / 价格生成，最多 3 条。 */
    public List<String> starterSuggestions() {
        List<Billboard> live = billboardRepository.findAll().stream()
                .filter(b -> !b.isDeleted())
                .toList();
        if (live.isEmpty()) {
            return List.of("可租点位推荐", "最便宜的点位", "适合汽车品牌的点位");
        }
        List<Billboard> available = live.stream()
                .filter(b -> b.getStatus() == BillboardStatus.AVAILABLE)
                .toList();
        List<Billboard> base = available.isEmpty() ? live : available;

        String topRoad = base.stream()
                .filter(b -> b.getRoad() != null && !b.getRoad().isBlank())
                .collect(Collectors.groupingBy(Billboard::getRoad, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        List<String> suggestions = new ArrayList<>();
        if (topRoad != null) {
            suggestions.add(shortenRoad(topRoad) + "有哪些点位");
        }
        boolean hasBudget = base.stream().anyMatch(b -> {
            Double price = KnowledgeQueryAnalyzer.monthlyPrice(b);
            return price != null && price <= 30000;
        });
        if (hasBudget) {
            suggestions.add("3 万以内可租点位");
        }
        suggestions.add("最便宜的可租点位");
        if (suggestions.size() < 3) {
            suggestions.add("适合汽车品牌的点位");
        }
        return suggestions.stream().distinct().limit(3).toList();
    }

    /**
     * 多轮对话后的“猜你喜欢”：从最近用户问题中提取预算 / 路段 / 状态偏好，
     * 排除当前回答已展示的广告牌，再推荐 3 条。
     */
    public GuessYouLikeVO guessYouLike(List<ChatTurn> history, List<Long> excludeIds) {
        if (history == null || history.isEmpty()) {
            return new GuessYouLikeVO("", List.of());
        }
        List<String> userQuestions = new ArrayList<>();
        for (ChatTurn turn : history) {
            if (turn != null && "user".equals(turn.role())
                    && turn.content() != null && !turn.content().isBlank()) {
                userQuestions.add(turn.content().trim());
            }
        }
        if (userQuestions.size() < 2) {
            return new GuessYouLikeVO("", List.of());
        }

        List<Billboard> live = billboardRepository.findAll().stream()
                .filter(b -> !b.isDeleted())
                .toList();
        if (live.isEmpty()) {
            return new GuessYouLikeVO("暂时没有更多可推荐的点位。", List.of());
        }

        String lastQuestion = userQuestions.get(userQuestions.size() - 1);
        String query = lastQuestion;
        if (userQuestions.size() >= 2 && isFollowUp(lastQuestion)
                && !queryAnalyzer.hasLocationKeyword(lastQuestion, live)) {
            query = userQuestions.get(userQuestions.size() - 2) + " " + lastQuestion;
        }
        KnowledgeQueryAnalyzer.QueryIntent intent = queryAnalyzer.analyze(query, live);
        Set<Long> excluded = excludeIds == null ? Set.of() : new HashSet<>(excludeIds);

        List<Billboard> matched = live.stream()
                .filter(b -> intent.status() == null || b.getStatus() == intent.status())
                .filter(b -> intent.locationKeyword() == null
                        || KnowledgeQueryAnalyzer.containsLocation(b, intent.locationKeyword()))
                .filter(b -> {
                    Double price = KnowledgeQueryAnalyzer.monthlyPrice(b);
                    if (intent.maxMonthlyPrice() != null
                            && (price == null || price > intent.maxMonthlyPrice())) {
                        return false;
                    }
                    if (intent.minMonthlyPrice() != null
                            && (price == null || price < intent.minMonthlyPrice())) {
                        return false;
                    }
                    return true;
                })
                .toList();
        if (matched.isEmpty()) {
            return new GuessYouLikeVO("暂时没有更多符合偏好的点位。", List.of());
        }

        List<Billboard> fresh = matched.stream()
                .filter(b -> !excluded.contains(b.getId()))
                .toList();
        List<Billboard> repeated = matched.stream()
                .filter(b -> excluded.contains(b.getId()))
                .toList();

        Comparator<Billboard> priceAsc = Comparator.comparing(
                (Billboard b) -> KnowledgeQueryAnalyzer.monthlyPrice(b),
                Comparator.nullsLast(Comparator.<Double>naturalOrder()));
        if (fresh.isEmpty() && !repeated.isEmpty()) {
            // 当前偏好下没有新点位时，推荐其他可租点位，而不是机械地重复参考项。
            List<Billboard> alternatives = new ArrayList<>(live.stream()
                    .filter(b -> b.getStatus() == BillboardStatus.AVAILABLE)
                    .filter(b -> !excluded.contains(b.getId()))
                    .toList());
            alternatives.sort(priceAsc.thenComparing(
                    b -> b.getName() == null ? "" : b.getName()));
            if (!alternatives.isEmpty()) {
                List<KnowledgeSourceVO> otherItems = alternatives.stream()
                        .limit(3)
                        .map(b -> sourceOf(b, "GUESS"))
                        .toList();
                return new GuessYouLikeVO("当前条件下没有更多新点位，给你看看其他可租点位：", otherItems);
            }
        }

        Comparator<Billboard> byPrice = priceAsc;
        if (intent.priceSort() == KnowledgeQueryAnalyzer.PriceSort.DESC) {
            byPrice = Comparator.comparing(
                    (Billboard b) -> KnowledgeQueryAnalyzer.monthlyPrice(b),
                    Comparator.nullsLast(Comparator.<Double>reverseOrder()));
        }
        Comparator<Billboard> order = Comparator
                .comparing((Billboard b) -> !excluded.contains(b.getId()))
                .thenComparing((Billboard b) -> b.getStatus() != BillboardStatus.AVAILABLE)
                .thenComparing(byPrice)
                .thenComparing((Billboard b) -> b.getName() == null ? "" : b.getName());

        List<Billboard> pool = new ArrayList<>(matched);
        pool.sort(order);

        List<KnowledgeSourceVO> items = pool.stream()
                .limit(3)
                .map(b -> sourceOf(b, "GUESS"))
                .toList();
        String summary;
        if (fresh.isEmpty() && !repeated.isEmpty()) {
            summary = "当前条件下没有更多新点位了，先回顾这些：";
        } else if (intent.hasFilterOrSort() || intent.locationKeyword() != null) {
            summary = "根据你最近关注的" + intent.describe() + "，继续为你推荐：";
        } else {
            summary = "根据你最近的选牌对话，继续为你推荐：";
        }
        return new GuessYouLikeVO(summary, items);
    }

    private String shortenRoad(String road) {
        return road == null ? "" : road.replaceAll("[A-Z]\\d+$", "").trim();
    }

    private KnowledgeSourceVO sourceOf(Billboard b, String matchType) {
        return new KnowledgeSourceVO(
                b.getId(),
                b.getName(),
                b.getCode(),
                b.getRoad(),
                b.getSection(),
                b.getPrice(),
                b.getPriceUnit(),
                b.getStatus().name(),
                b.getStatus().label(),
                0,
                matchType);
    }

    /**
     * 知识库问答（SSE 流式）：先推送参考广告牌元数据，再推送模型增量文本，最后推送完整结果。
     * 运行在独立 qaExecutor 上，由控制器立即返回 SseEmitter。
     */
    @Async("qaExecutor")
    public void streamAsk(String question, Integer topKRequest, String modelId,
                          List<ChatTurn> history, SseEmitter emitter) {
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(e -> closed.set(true));

        long start = System.currentTimeMillis();
        ChatCompletionService.ResolvedModel model;
        try {
            model = chatService.resolveModel(modelId);
        } catch (Exception e) {
            if (!closed.get()) {
                boolean sent = sendEvent(emitter, closed, "error", Map.of(
                        "message", messageOf(e)));
                if (sent) {
                    emitter.complete();
                }
            }
            return;
        }

        if (intentClassifier.classify(question, history, false) == UserIntent.CHAT) {
            streamChatReply(emitter, closed, model, question, history, start);
            return;
        }

        AskContext ctx;
        try {
            ctx = prepare(question, topKRequest, history);
        } catch (Exception e) {
            log.warn("流式问答检索失败：{}", e.getMessage());
            if (!closed.get()) {
                boolean sent = sendEvent(emitter, closed, "error", Map.of(
                        "message", messageOf(e)));
                if (sent) {
                    emitter.complete();
                }
            }
            return;
        }

        boolean metaSent = sendEvent(emitter, closed, "meta", Map.of(
                "sources", ctx.sources(),
                "mode", "billboard",
                "llmConfigured", chatService.isConfigured(),
                "llmModel", model.id(),
                "llmModelLabel", model.label(),
                "embeddingModel", embeddingService.model(),
                "structured", ctx.intent() != null && ctx.intent().hasFilterOrSort(),
                "tookMs", System.currentTimeMillis() - start));
        if (!metaSent) {
            return;
        }

        if (ctx.immediateAnswer() != null) {
            if (sendDelta(emitter, closed, ctx.immediateAnswer())) {
                finish(emitter, closed, ctx.sources(), model, ctx.immediateAnswer(), start);
            }
            return;
        }

        if (!chatService.isConfigured()) {
            String fallback = "未配置可用的问答模型（可在后台「API 管理」中添加），以下为知识库检索结果：\n\n"
                    + fallbackList(ctx.sources());
            if (sendDelta(emitter, closed, fallback)) {
                finish(emitter, closed, ctx.sources(), model, fallback, start);
            }
            return;
        }

        streamModelAnswer(emitter, closed, model, SYSTEM_PROMPT,
                buildUserPrompt(ctx.question(), ctx.items(), ctx.intent(),
                        ctx.totalMatches(), history, ctx.broadRecommendation()),
                ctx.sources(), start);
    }

    /** 普通聊天：不检索广告牌、不返回参考来源。 */
    private void streamChatReply(SseEmitter emitter, AtomicBoolean closed,
                                 ChatCompletionService.ResolvedModel model,
                                 String question, List<ChatTurn> history, long start) {
        boolean metaSent = sendEvent(emitter, closed, "meta", Map.of(
                "sources", List.of(),
                "mode", "chat",
                "llmConfigured", chatService.isConfigured(),
                "llmModel", model.id(),
                "llmModelLabel", model.label(),
                "embeddingModel", embeddingService.model(),
                "structured", false,
                "tookMs", System.currentTimeMillis() - start));
        if (!metaSent) {
            return;
        }
        if (!chatService.isConfigured()) {
            String text = "当前未配置可用的对话模型，暂时无法聊天。";
            if (sendDelta(emitter, closed, text)) {
                finish(emitter, closed, List.of(), model, text, start, "chat");
            }
            return;
        }
        streamModelAnswer(emitter, closed, model, CHAT_SYSTEM_PROMPT,
                buildChatPrompt(question, history), List.of(), start, "chat");
    }

    /** 调用模型流式生成并以 delta 事件推送；文本和图片问答共用。 */
    private void streamModelAnswer(SseEmitter emitter, AtomicBoolean closed,
                                   ChatCompletionService.ResolvedModel model,
                                   String systemPrompt, String userPrompt,
                                   List<KnowledgeSourceVO> sources, long start) {
        streamModelAnswer(emitter, closed, model, systemPrompt, userPrompt, sources, start, "billboard");
    }

    private void streamModelAnswer(SseEmitter emitter, AtomicBoolean closed,
                                   ChatCompletionService.ResolvedModel model,
                                   String systemPrompt, String userPrompt,
                                   List<KnowledgeSourceVO> sources, long start, String mode) {
        StringBuilder full = new StringBuilder();
        boolean completed;
        try {
            completed = chatService.stream(systemPrompt, userPrompt, model, delta -> {
                if (closed.get()) {
                    return false;
                }
                full.append(delta);
                return sendDelta(emitter, closed, delta);
            });
        } catch (Exception e) {
            if (closed.get()) {
                return;
            }
            log.warn("流式问答模型调用失败：{}", e.getMessage());
            String text;
            if (full.length() == 0) {
                text = "chat".equals(mode)
                        ? "当前对话模型暂时不可用（" + messageOf(e) + "）。"
                        : "问答模型暂时不可用（" + messageOf(e) + "），以下为知识库检索结果：\n\n"
                          + fallbackList(sources);
            } else {
                text = full + "\n\n（回答生成中断：" + messageOf(e) + "）";
            }
            String suffix = text.substring(full.length());
            if (sendDelta(emitter, closed, suffix)) {
                finish(emitter, closed, sources, model, text, start, mode);
            }
            return;
        }

        if (closed.get() || !completed) {
            return;
        }
        String answer = full.toString();
        if (answer.isBlank()) {
            answer = "chat".equals(mode)
                    ? "当前对话模型没有返回内容，请稍后再试。"
                    : "问答模型没有返回内容，以下为知识库检索结果：\n\n"
                      + fallbackList(sources);
            if (!sendDelta(emitter, closed, answer)) {
                return;
            }
        }
        finish(emitter, closed, sources, model, answer, start, mode);
    }

    /**
     * 图片选牌（SSE 流式）：本地视觉比对 → 元数据 → 大模型语义分析。
     * 上传图片只在内存中处理，不落盘。
     */
    @Async("qaExecutor")
    public void streamImageAsk(byte[] imageBytes, String imageMime, String question, String modelId,
                               Integer topKRequest, List<ChatTurn> history, SseEmitter emitter) {
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(e -> closed.set(true));

        long start = System.currentTimeMillis();
        ChatCompletionService.ResolvedModel model;
        try {
            model = chatService.resolveModel(modelId);
        } catch (Exception e) {
            sendError(emitter, closed, messageOf(e));
            return;
        }

        if (intentClassifier.classify(question, history, true) == UserIntent.CHAT) {
            streamImageChat(emitter, closed, model, imageBytes, imageMime, question, history, start);
            return;
        }

        ImageSimilarityService.MatchResult result;
        try {
            int topK = topKRequest == null ? 5 : topKRequest;
            topK = Math.max(1, Math.min(MAX_TOP_K, topK));
            result = imageSimilarityService.match(imageBytes, topK);
        } catch (Exception e) {
            log.warn("图片视觉比对失败：{}", e.getMessage());
            sendError(emitter, closed, messageOf(e));
            return;
        }

        List<KnowledgeSourceVO> sources = result.matches().stream()
                .map(m -> new KnowledgeSourceVO(
                        m.billboard().getId(),
                        m.billboard().getName(),
                        m.billboard().getCode(),
                        m.billboard().getRoad(),
                        m.billboard().getSection(),
                        m.billboard().getPrice(),
                        m.billboard().getPriceUnit(),
                        m.billboard().getStatus().name(),
                        m.billboard().getStatus().label(),
                        m.score(),
                        "IMAGE"))
                .toList();

        Map<String, Object> meta = new HashMap<>();
        meta.put("sources", sources);
        meta.put("mode", "billboard");
        meta.put("imageMatch", true);
        meta.put("visualFeatures", Map.of(
                "orientation", result.summary().orientation(),
                "brightness", result.summary().brightness(),
                "colorfulness", result.summary().colorfulness(),
                "dominantColors", result.summary().dominantColors(),
                "comparedCount", result.summary().comparedCount()));
        meta.put("llmConfigured", chatService.isConfigured());
        meta.put("llmModel", model.id());
        meta.put("llmModelLabel", model.label());
        meta.put("embeddingModel", embeddingService.model());
        meta.put("tookMs", System.currentTimeMillis() - start);
        if (!sendEvent(emitter, closed, "meta", meta)) {
            return;
        }

        if (!chatService.isConfigured()) {
            String fallback = "未配置可用的问答模型（可在后台「API 管理」中添加），"
                    + "暂时无法识别图片文字 / 画面，仅返回视觉相似广告牌：\n\n"
                    + fallbackList(sources);
            if (sendDelta(emitter, closed, fallback)) {
                finish(emitter, closed, sources, model, fallback, start);
            }
            return;
        }

        String q = question == null ? "" : question.trim();
        String imagePrompt = buildImagePrompt(q, result, history, true);
        String fallbackPrompt = buildImagePrompt(q, result, history, false);
        String imageDataUrl = visionDataUrl(imageBytes, imageMime);
        streamVisionAnswer(emitter, closed, model, imageDataUrl,
                imagePrompt, fallbackPrompt, sources, start);
    }

    /** 视觉模型优先；模型不支持图片或调用失败时，降级为本地视觉特征分析。 */
    private void streamVisionAnswer(SseEmitter emitter, AtomicBoolean closed,
                                    ChatCompletionService.ResolvedModel model,
                                    String imageDataUrl, String imagePrompt,
                                    String fallbackPrompt,
                                    List<KnowledgeSourceVO> sources, long start) {
        StringBuilder full = new StringBuilder();
        try {
            boolean completed = chatService.streamVision(
                    IMAGE_SYSTEM_PROMPT, imagePrompt, imageDataUrl, model, delta -> {
                        if (closed.get()) {
                            return false;
                        }
                        full.append(delta);
                        return sendDelta(emitter, closed, delta);
                    });
            if (closed.get() || !completed) {
                return;
            }
            if (full.length() == 0) {
                streamModelAnswer(emitter, closed, model, IMAGE_FALLBACK_PROMPT,
                        fallbackPrompt, sources, start);
                return;
            }
            finish(emitter, closed, sources, model, full.toString(), start);
        } catch (Exception e) {
            if (closed.get()) {
                return;
            }
            log.warn("视觉模型调用失败，降级为本地视觉特征分析：{}", e.getMessage());
            if (full.length() == 0) {
                streamModelAnswer(emitter, closed, model, IMAGE_FALLBACK_PROMPT,
                        fallbackPrompt, sources, start);
                return;
            }
            String suffix = "\n\n（图片识别中断：" + messageOf(e) + "）";
            if (sendDelta(emitter, closed, suffix)) {
                finish(emitter, closed, sources, model, full + suffix, start);
            }
        }
    }

    /** 图片识别 / 普通聊天：只做 OCR 与画面理解，不推荐广告牌。 */
    private void streamImageChat(SseEmitter emitter, AtomicBoolean closed,
                                 ChatCompletionService.ResolvedModel model,
                                 byte[] imageBytes, String imageMime,
                                 String question, List<ChatTurn> history, long start) {
        ImageSimilarityService.Summary summary;
        try {
            summary = imageSimilarityService.summarize(imageBytes);
        } catch (Exception e) {
            sendError(emitter, closed, messageOf(e));
            return;
        }

        Map<String, Object> meta = new HashMap<>();
        meta.put("sources", List.of());
        meta.put("mode", "chat");
        meta.put("imageMatch", true);
        meta.put("visualFeatures", Map.of(
                "orientation", summary.orientation(),
                "brightness", summary.brightness(),
                "colorfulness", summary.colorfulness(),
                "dominantColors", summary.dominantColors(),
                "comparedCount", 0));
        meta.put("llmConfigured", chatService.isConfigured());
        meta.put("llmModel", model.id());
        meta.put("llmModelLabel", model.label());
        meta.put("embeddingModel", embeddingService.model());
        meta.put("tookMs", System.currentTimeMillis() - start);
        if (!sendEvent(emitter, closed, "meta", meta)) {
            return;
        }

        if (!chatService.isConfigured()) {
            String text = "当前未配置可用的视觉模型，暂时无法识别图片。";
            if (sendDelta(emitter, closed, text)) {
                finish(emitter, closed, List.of(), model, text, start, "chat");
            }
            return;
        }

        String imageDataUrl = visionDataUrl(imageBytes, imageMime);
        String prompt = buildImageChatPrompt(question, history);
        streamVisionChat(emitter, closed, model, imageDataUrl, prompt, start);
    }

    private void streamVisionChat(SseEmitter emitter, AtomicBoolean closed,
                                  ChatCompletionService.ResolvedModel model,
                                  String imageDataUrl, String prompt, long start) {
        StringBuilder full = new StringBuilder();
        try {
            boolean completed = chatService.streamVision(
                    IMAGE_CHAT_SYSTEM_PROMPT, prompt, imageDataUrl, model, delta -> {
                        if (closed.get()) {
                            return false;
                        }
                        full.append(delta);
                        return sendDelta(emitter, closed, delta);
                    });
            if (closed.get() || !completed) {
                return;
            }
            if (full.length() == 0) {
                String text = "图片识别暂时没有返回内容，请稍后再试。";
                if (sendDelta(emitter, closed, text)) {
                    finish(emitter, closed, List.of(), model, text, start, "chat");
                }
                return;
            }
            finish(emitter, closed, List.of(), model, full.toString(), start, "chat");
        } catch (Exception e) {
            if (closed.get()) {
                return;
            }
            log.warn("图片识别聊天调用失败：{}", e.getMessage());
            String text = full.length() == 0
                    ? "当前视觉模型暂时不可用（" + messageOf(e) + "）。"
                    : full + "\n\n（图片识别中断：" + messageOf(e) + "）";
            String suffix = text.substring(full.length());
            if (sendDelta(emitter, closed, suffix)) {
                finish(emitter, closed, List.of(), model, text, start, "chat");
            }
        }
    }

    private String buildImageChatPrompt(String question, List<ChatTurn> history) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("【识图任务】\n")
                .append("1. 识别图片中的文字（如有）；\n")
                .append("2. 描述画面中的主要物体、场景和可见品牌线索；看不清或不确定时明确说明。\n\n");
        String historyText = historyText(history);
        if (!historyText.isBlank()) {
            sb.append("【对话历史】\n").append(historyText).append('\n');
        }
        sb.append("【用户问题】\n")
                .append(question == null || question.isBlank()
                        ? "请识别并描述这张图片。"
                        : question.trim())
                .append("\n\n这是普通图片识别 / 聊天场景：不要推荐广告牌，不要提及广告牌资料或租赁报价；回答简洁。");
        return sb.toString();
    }

    /** 把上传图片转成 data URL；过大图片缩放到 1600px 内，避免请求体过大。 */
    private String visionDataUrl(byte[] imageBytes, String imageMime) {
        String mime = imageMime == null || imageMime.isBlank() ? "image/png" : imageMime;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int maxSide = 1600;
            if (width <= maxSide && height <= maxSide) {
                return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
            }
            double scale = Math.min(maxSide / (double) width, maxSide / (double) height);
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            graphics.dispose();

            String format = mime.toLowerCase().contains("jpeg") ? "jpg" : "png";
            String outMime = "jpg".equals(format) ? "image/jpeg" : "image/png";
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(scaled, format, output);
            return "data:" + outMime + ";base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception e) {
            log.warn("图片缩放失败，使用原图：{}", e.getMessage());
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean closed, String message) {
        if (closed.get()) {
            return;
        }
        boolean sent = sendEvent(emitter, closed, "error", Map.of("message", message));
        if (sent) {
            emitter.complete();
        }
    }

    private String buildImagePrompt(String question, ImageSimilarityService.MatchResult result,
                                    List<ChatTurn> history, boolean vision) {
        ImageSimilarityService.Summary summary = result.summary();
        StringBuilder sb = new StringBuilder(2048);
        if (vision) {
            sb.append("【识图任务】\n")
                    .append("1. 识别图片中的全部文字（中文、英文、数字，逐条列出原文）\n")
                    .append("2. 描述画面中的主要物体、场景、构图和主色\n")
                    .append("3. 判断可见的品牌 / 行业 / 产品线索；看不清或不确定时明确说明\n")
                    .append("4. 结合下方参考广告牌分析相似性，并给出选牌建议\n\n");
        }
        String historyText = historyText(history);
        if (!historyText.isBlank()) {
            sb.append("【对话历史】\n").append(historyText)
                    .append("（历史仅用于理解追问；当前资料以本轮图片和【视觉相似广告牌】为准）\n\n");
        }
        sb.append("【本地视觉特征】\n")
                .append("构图：").append(summary.orientation()).append('\n')
                .append("主色：").append(String.join("、", summary.dominantColors())).append('\n')
                .append("亮度：").append(summary.brightness()).append('\n')
                .append("色彩：").append(summary.colorfulness()).append('\n')
                .append("已比对广告牌图片：").append(summary.comparedCount()).append(" 张\n");
        sb.append("\n【视觉相似广告牌】\n");
        if (result.matches().isEmpty()) {
            sb.append("本地视觉比对没有找到足够相似的广告牌；请仅依据图片识别结果和用户需求回答。\n");
        } else {
            int index = 1;
            for (ImageSimilarityService.Match match : result.matches()) {
                sb.append("资料").append(index++).append("：\n")
                        .append(contentOf(match.billboard())).append('\n')
                        .append("视觉相似度：").append(percent(match.score()))
                        .append("%（颜色 ").append(percent(match.colorScore()))
                        .append("% / 结构 ").append(percent(match.hashScore()))
                        .append("% / 构图 ").append(percent(match.aspectScore())).append("%）\n");
                appendMonthlyPriceNote(sb, match.billboard());
            }
        }
        sb.append("\n【用户问题】\n")
                .append(question.isBlank()
                        ? "请识别图片内容，并推荐相似的广告牌。"
                        : question);
        if (vision) {
            sb.append("\n\n请先输出【图片识别结果】（逐条列出识别到的文字、画面理解、品牌线索），"
                    + "再结合参考广告牌分析相似性并给出选牌建议；无法确认的内容必须明确说明。");
        } else {
            sb.append("\n\n请仅根据本地视觉特征和广告牌资料分析相似性，"
                    + "不要声称识别了图片中的文字、物体或品牌。");
        }
        return sb.toString();
    }

    private double percent(double value) {
        return Math.round(value * 1000) / 10.0;
    }

    /** 发送命名 SSE 事件；返回 false 表示客户端已断开。 */
    private boolean sendEvent(SseEmitter emitter, AtomicBoolean closed, String name, Object data) {
        if (closed.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            closed.set(true);
            return false;
        }
    }

    private boolean sendDelta(SseEmitter emitter, AtomicBoolean closed, String text) {
        return sendEvent(emitter, closed, "delta", Map.of("text", text == null ? "" : text));
    }

    private void finish(SseEmitter emitter, AtomicBoolean closed, List<KnowledgeSourceVO> sources,
                        ChatCompletionService.ResolvedModel model, String answer, long start) {
        finish(emitter, closed, sources, model, answer, start, "billboard");
    }

    private void finish(SseEmitter emitter, AtomicBoolean closed, List<KnowledgeSourceVO> sources,
                        ChatCompletionService.ResolvedModel model, String answer, long start,
                        String mode) {
        if (closed.get()) {
            return;
        }
        boolean sent = sendEvent(emitter, closed, "done", Map.of(
                "answer", answer,
                "sources", sources,
                "mode", mode,
                "llmConfigured", chatService.isConfigured(),
                "llmModel", model.id(),
                "llmModelLabel", model.label(),
                "embeddingModel", embeddingService.model(),
                "tookMs", System.currentTimeMillis() - start));
        if (sent) {
            emitter.complete();
        }
    }

    private String messageOf(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? "问答失败，请稍后重试" : e.getMessage();
    }

    private KnowledgeAnswerVO answer(String text, List<KnowledgeSourceVO> sources, long start,
                                     ChatCompletionService.ResolvedModel model) {
        return new KnowledgeAnswerVO(
                text,
                sources,
                chatService.isConfigured(),
                model.id(),
                embeddingService.model(),
                System.currentTimeMillis() - start);
    }

    /**
     * 追问时把上一轮用户问题拼进检索词，例如“还有呢”“那最便宜的呢”。
     * 如果当前问题已经包含明确的地名 / 公路词（如“那杭州的呢”），则只检索当前问题，避免旧主题干扰。
     */
    private String retrievalQuery(String question, List<ChatTurn> history,
                                  Collection<Billboard> billboards) {
        if (history == null || history.isEmpty() || !isFollowUp(question)) {
            return question;
        }
        if (queryAnalyzer.hasLocationKeyword(question, billboards)) {
            return question;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatTurn turn = history.get(i);
            if (turn != null && "user".equals(turn.role())
                    && turn.content() != null && !turn.content().isBlank()) {
                return turn.content().trim() + " " + question;
            }
        }
        return question;
    }

    private boolean isFollowUp(String question) {
        return question.contains("那") || question.contains("这个")
                || question.contains("它") || question.contains("还有")
                || question.contains("再") || question.contains("呢")
                || question.contains("刚才") || question.contains("上面")
                || question.contains("换成") || question.contains("比较")
                || question.contains("其中");
    }

    /** 只带最近 6 条消息，且每条截断，避免历史把上下文撑爆。 */
    private String historyText(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        List<ChatTurn> valid = new ArrayList<>();
        for (ChatTurn turn : history) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String role = turn.role();
            if ("user".equals(role) || "assistant".equals(role)) {
                valid.add(turn);
            }
        }
        if (valid.isEmpty()) {
            return "";
        }
        int from = Math.max(0, valid.size() - 6);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < valid.size(); i++) {
            ChatTurn turn = valid.get(i);
            String content = turn.content().trim();
            if (content.length() > 600) {
                content = content.substring(0, 600) + "…";
            }
            sb.append("user".equals(turn.role()) ? "用户：" : "助手：")
                    .append(content).append('\n');
        }
        return sb.toString();
    }

    private String buildChatPrompt(String question, List<ChatTurn> history) {
        StringBuilder sb = new StringBuilder();
        String historyText = historyText(history);
        if (!historyText.isBlank()) {
            sb.append("【对话历史】\n").append(historyText).append('\n');
        }
        sb.append("【用户问题】\n").append(question == null ? "" : question.trim());
        return sb.toString();
    }

    private String buildUserPrompt(String question, List<Scored> top,
                                   KnowledgeQueryAnalyzer.QueryIntent intent, int totalMatches,
                                   List<ChatTurn> history, boolean broadRecommendation) {
        StringBuilder sb = new StringBuilder(2048);
        String historyText = historyText(history);
        if (!historyText.isBlank()) {
            sb.append("【对话历史】\n").append(historyText)
                    .append("（历史仅用于理解追问；当前资料以本轮【广告牌资料】为准）\n\n");
        }
        if (broadRecommendation) {
            sb.append("【推荐说明】用户还没有给出明确的预算 / 路段 / 品牌偏好。")
                    .append("以下是从现有可租赁广告牌中挑选的代表性候选；请以小金桥的口吻推荐 3 个左右，")
                    .append("说明各自适合的投放场景，并追问用户的预算、路段或品牌需求。")
                    .append("不要机械地说没有资料。\n\n");
        }
        sb.append("【广告牌资料】\n");
        int index = 1;
        for (Scored s : top) {
            sb.append("资料").append(index++).append("：\n")
                    .append(s.content()).append('\n');
            appendMonthlyPriceNote(sb, s.billboard());
        }
        if (intent.hasFilterOrSort()) {
            sb.append("\n【检索说明】");
            if (intent.priceSort() != null) {
                // 已取排序后的前 N 条，最有利的候选一定在其中；避免模型担心“还有更便宜的没列出来”。
                sb.append("以上资料已按价格（年付/季付等折算为月均价）")
                        .append(intent.priceSort() == KnowledgeQueryAnalyzer.PriceSort.ASC
                                ? "从低到高" : "从高到低")
                        .append("排序，此处列出前 ").append(top.size())
                        .append(" 条，已覆盖最优候选；请以这些资料为准逐条核对。\n");
            } else {
                sb.append("以上资料是按用户条件（").append(intent.describe())
                        .append("）从全部广告牌中筛选的结果，共 ").append(totalMatches).append(" 条");
                if (totalMatches > top.size()) {
                    sb.append("，以下为其中前 ").append(top.size()).append(" 条");
                } else {
                    sb.append("，已全部列出");
                }
                sb.append("。请逐条核对，不要遗漏符合条件的资料。\n");
            }
        }
        sb.append("\n【用户问题】\n").append(question).append('\n')
                .append("\n请依据上述资料回答；资料不足时明确说明。");
        return sb.toString();
    }

    /** 年付 / 季付 / 日付报价补充月均折算，方便模型横向比较。 */
    private void appendMonthlyPriceNote(StringBuilder sb, Billboard b) {
        Double monthly = KnowledgeQueryAnalyzer.monthlyPrice(b);
        String unit = b.getPriceUnit();
        if (monthly == null || unit == null || unit.contains("月")) {
            return;
        }
        sb.append("月均折算：¥").append(KnowledgeQueryAnalyzer.money(monthly))
                .append(" 元/月（原报价 ¥")
                .append(b.getPrice().stripTrailingZeros().toPlainString())
                .append(' ').append(unit).append("，仅用于横向比较）\n");
    }

    /**
     * 按硬条件（价格区间 / 租赁状态 / 位置关键词）从全部广告牌中筛选，
     * 再按价格或语义相关度排序；这样“3 万以内”“最便宜”不会漏掉向量 topK 之外的资料。
     */
    private StructuredResult structuredCandidates(KnowledgeQueryAnalyzer.QueryIntent intent,
                                                  Map<Long, Billboard> billboards,
                                                  List<Scored> scored) {
        Map<Long, Scored> scoredById = scored.stream()
                .collect(Collectors.toMap(s -> s.billboard().getId(), s -> s, (a, b) -> a));
        List<Scored> matches = new ArrayList<>();
        for (Billboard b : billboards.values()) {
            if (intent.status() != null && b.getStatus() != intent.status()) {
                continue;
            }
            if (intent.locationKeyword() != null
                    && !KnowledgeQueryAnalyzer.containsLocation(b, intent.locationKeyword())) {
                continue;
            }
            Double monthly = KnowledgeQueryAnalyzer.monthlyPrice(b);
            if (intent.hasPriceFilter() && monthly == null) {
                continue;
            }
            if (intent.maxMonthlyPrice() != null
                    && (monthly == null || monthly > intent.maxMonthlyPrice())) {
                continue;
            }
            if (intent.minMonthlyPrice() != null
                    && (monthly == null || monthly < intent.minMonthlyPrice())) {
                continue;
            }
            Scored s = scoredById.get(b.getId());
            if (s != null) {
                matches.add(s);
            }
        }

        Comparator<Scored> priceAsc = Comparator.comparing(
                (Scored s) -> KnowledgeQueryAnalyzer.monthlyPrice(s.billboard()),
                Comparator.nullsLast(Comparator.<Double>naturalOrder()));
        Comparator<Scored> priceDesc = Comparator.comparing(
                (Scored s) -> KnowledgeQueryAnalyzer.monthlyPrice(s.billboard()),
                Comparator.nullsLast(Comparator.<Double>reverseOrder()));
        Comparator<Scored> order = (intent.priceSort() == KnowledgeQueryAnalyzer.PriceSort.DESC
                ? priceDesc : priceAsc)
                .thenComparing(Comparator.comparingDouble(Scored::score).reversed());
        matches.sort(order);

        int total = matches.size();
        // 价格排序只需保证最优的若干条进入上下文；价格/状态筛选则尽量给全量符合项。
        int limit = intent.priceSort() != null ? MAX_TOP_K : MAX_CONTEXT;
        if (matches.size() > limit) {
            matches = new ArrayList<>(matches.subList(0, limit));
        }
        return new StructuredResult(matches, total);
    }

    private String fallbackList(List<KnowledgeSourceVO> sources) {
        StringBuilder sb = new StringBuilder(512);
        int index = 1;
        for (KnowledgeSourceVO s : sources) {
            sb.append(index++).append(". ").append(s.name())
                    .append("（").append(s.code()).append("）：");
            if (s.road() != null && !s.road().isBlank()) {
                sb.append(s.road());
            }
            if (s.section() != null && !s.section().isBlank()) {
                sb.append(' ').append(s.section());
            }
            sb.append("，").append(s.statusLabel()).append("，");
            if (s.price() == null) {
                sb.append("报价面议");
            } else {
                sb.append("¥").append(s.price().stripTrailingZeros().toPlainString());
                if (s.priceUnit() != null) {
                    sb.append(' ').append(s.priceUnit());
                }
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    // ==================== 状态与重建 ====================

    /** 后台知识库状态。 */
    public KnowledgeStatusVO status() {
        List<Billboard> billboards = billboardRepository.findAll(
                (root, query, cb) -> cb.isFalse(root.get("deleted")));
        billboards.sort(Comparator.comparing(Billboard::getId));
        Map<Long, KnowledgeDocument> docs = documentRepository.findAll().stream()
                .collect(Collectors.toMap(KnowledgeDocument::getBillboardId, d -> d, (a, b) -> a));

        List<KnowledgeDocumentVO> items = new ArrayList<>(billboards.size());
        int ready = 0;
        int error = 0;
        LocalDateTime lastIndexed = null;
        for (Billboard b : billboards) {
            KnowledgeDocument d = docs.get(b.getId());
            String state = d == null ? PENDING : d.getStatus();
            if (READY.equals(state)) {
                ready++;
            } else if (ERROR.equals(state)) {
                error++;
            }
            if (d != null && d.getIndexedAt() != null
                    && (lastIndexed == null || d.getIndexedAt().isAfter(lastIndexed))) {
                lastIndexed = d.getIndexedAt();
            }
            items.add(new KnowledgeDocumentVO(
                    b.getId(),
                    b.getName(),
                    b.getCode(),
                    state,
                    d != null ? d.getModel() : embeddingService.model(),
                    d != null ? d.getDimension() : null,
                    d != null ? d.getIndexedAt() : null,
                    d != null ? d.getErrorMessage() : null));
        }
        int pending = Math.max(0, billboards.size() - ready - error);
        return new KnowledgeStatusVO(
                isEnabled(),
                isEnabled() && embeddingService.isAvailable(),
                embeddingService.model(),
                chatService.isConfigured(),
                chatService.model(),
                billboards.size(),
                ready,
                pending,
                error,
                lastIndexed,
                items);
    }

    /** 后台手动重建全部索引（强制重新向量化）。 */
    public KnowledgeStatusVO rebuildAll() {
        if (!isEnabled()) {
            throw new BadRequestException("知识库功能未启用");
        }
        return doRebuild(true);
    }

    /**
     * 重建索引。
     * force=false：只处理缺失 / 错误 / 内容或模型变化的文档（启动时用）；
     * force=true ：全部重新向量化（后台手动重建用）。
     */
    private synchronized KnowledgeStatusVO doRebuild(boolean force) {
        if (!isEnabled()) {
            throw new BadRequestException("知识库功能未启用");
        }
        List<Billboard> billboards = billboardRepository.findAll(
                (root, query, cb) -> cb.isFalse(root.get("deleted")));
        billboards.sort(Comparator.comparing(Billboard::getId));
        Map<Long, KnowledgeDocument> docs = documentRepository.findAll().stream()
                .collect(Collectors.toMap(KnowledgeDocument::getBillboardId, d -> d, (a, b) -> a));

        Set<Long> liveIds = billboards.stream().map(Billboard::getId).collect(Collectors.toCollection(HashSet::new));
        for (KnowledgeDocument d : docs.values()) {
            if (!liveIds.contains(d.getBillboardId())) {
                try {
                    documentRepository.delete(d);
                } catch (Exception e) {
                    log.warn("清理失效知识文档失败 billboardId={}：{}", d.getBillboardId(), e.getMessage());
                }
            }
        }

        String model = embeddingService.model();
        String signature = embeddingService.signature();
        Map<Long, String> contentMap = new HashMap<>();
        Map<Long, String> hashMap = new HashMap<>();
        List<Billboard> pending = new ArrayList<>();
        for (Billboard b : billboards) {
            String content = contentOf(b);
            String hash = hashOf(content, signature);
            contentMap.put(b.getId(), content);
            hashMap.put(b.getId(), hash);
            KnowledgeDocument d = docs.get(b.getId());
            boolean upToDate = d != null
                    && READY.equals(d.getStatus())
                    && hash.equals(d.getContentHash())
                    && model.equals(d.getModel())
                    && d.getEmbedding() != null;
            if (force || !upToDate) {
                pending.add(b);
            }
        }

        int failures = 0;
        for (int i = 0; i < pending.size(); i += BATCH_SIZE) {
            List<Billboard> batch = pending.subList(i, Math.min(i + BATCH_SIZE, pending.size()));
            List<String> contents = batch.stream().map(b -> contentMap.get(b.getId())).toList();
            try {
                List<List<Double>> vectors = embeddingService.embedAll(contents);
                for (int j = 0; j < batch.size(); j++) {
                    Billboard b = batch.get(j);
                    List<Double> vector = vectors.get(j);
                    KnowledgeDocument doc = docs.getOrDefault(b.getId(), new KnowledgeDocument());
                    doc.setBillboardId(b.getId());
                    doc.setContent(contentMap.get(b.getId()));
                    doc.setContentHash(hashMap.get(b.getId()));
                    doc.setModel(model);
                    doc.setEmbedding(vectorToJson(vector));
                    doc.setDimension(vector.size());
                    doc.setStatus(READY);
                    doc.setErrorMessage(null);
                    doc.setIndexedAt(LocalDateTime.now());
                    documentRepository.save(doc);
                }
            } catch (Exception e) {
                failures += batch.size();
                log.warn("知识库批量索引失败：{}", e.getMessage());
                for (Billboard b : batch) {
                    KnowledgeDocument doc = docs.getOrDefault(b.getId(), new KnowledgeDocument());
                    doc.setBillboardId(b.getId());
                    doc.setContent(contentMap.get(b.getId()));
                    doc.setContentHash(hashMap.get(b.getId()));
                    doc.setModel(model);
                    doc.setEmbedding(null);
                    doc.setDimension(null);
                    doc.setStatus(ERROR);
                    doc.setErrorMessage(truncate(e.getMessage(), 500));
                    doc.setIndexedAt(null);
                    documentRepository.save(doc);
                }
                break;
            }
        }
        log.info("知识库索引完成：广告牌 {} 条，本次处理 {} 条，失败 {} 条",
                billboards.size(), pending.size(), failures);
        return status();
    }

    // ==================== 知识文本 / 向量工具 ====================

    /** 把广告牌字段拼接为结构化知识文本。 */
    private String contentOf(Billboard b) {
        StringBuilder sb = new StringBuilder(512);
        line(sb, "广告牌名称", b.getName());
        line(sb, "广告牌编号", b.getCode());
        line(sb, "所在公路", b.getRoad());
        line(sb, "所属路段", b.getSection());
        line(sb, "详细位置", b.getAddress());
        line(sb, "租赁状态", b.getStatus() == null ? null : b.getStatus().label());
        if (b.getPrice() == null) {
            line(sb, "租赁报价", "面议");
        } else {
            String unit = b.getPriceUnit() == null ? "" : " " + b.getPriceUnit();
            line(sb, "租赁报价", "¥" + b.getPrice().stripTrailingZeros().toPlainString() + unit);
        }
        line(sb, "报价说明", b.getPriceNote());
        line(sb, "尺寸规格", b.getSize());
        line(sb, "画面材质", b.getMaterial());
        line(sb, "可视面积", b.getVisibleArea());
        line(sb, "主要受众", b.getAudience());
        line(sb, "补充说明", b.getDescription());
        // 数据库维护模块新增的字段 / 属性也纳入知识文本，便于语义检索
        try {
            for (BillboardFieldService.DisplayValue value : billboardFieldService.displayValues(b.getId())) {
                line(sb, value.label(), value.value());
            }
        } catch (Exception e) {
            log.debug("读取广告牌扩展字段失败，id={}：{}", b.getId(), e.getMessage());
        }
        return sb.toString().trim();
    }

    private void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append('：').append(value.trim()).append('\n');
        }
    }

    private String hashOf(String content, String model) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((content + "|" + model).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String vectorToJson(List<Double> vector) {
        try {
            return MAPPER.writeValueAsString(vector);
        } catch (Exception e) {
            throw new IllegalStateException("向量序列化失败", e);
        }
    }

    private List<Double> vectorFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Double>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private double cosine(List<Double> a, List<Double> b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 检索命中项（广告牌 + 知识文本 + 相似度）。 */
    private record Scored(Billboard billboard, String content, double score) {
    }

    /** 结构化筛选结果：items 为实际送入模型 / 返回前台的资料，total 为符合条件的总数。 */
    private record StructuredResult(List<Scored> items, int total) {
    }

    /** 检索后的问答上下文；immediateAnswer 非空表示无需调用模型。 */
    private record AskContext(String question,
                              List<Scored> items,
                              List<KnowledgeSourceVO> sources,
                              KnowledgeQueryAnalyzer.QueryIntent intent,
                              int totalMatches,
                              String immediateAnswer,
                              boolean broadRecommendation) {
    }
}
