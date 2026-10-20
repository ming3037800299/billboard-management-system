package com.jqgg.glm53.controller;

import com.jqgg.glm53.dto.ApiResult;
import com.jqgg.glm53.dto.ChatTurn;
import com.jqgg.glm53.dto.GuessYouLikeRequest;
import com.jqgg.glm53.dto.GuessYouLikeVO;
import com.jqgg.glm53.dto.KnowledgeAnswerVO;
import com.jqgg.glm53.dto.KnowledgeAskRequest;
import com.jqgg.glm53.dto.KnowledgeModelVO;
import com.jqgg.glm53.dto.KnowledgeStatusVO;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.service.KnowledgeService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * 知识库接口：
 *   POST /api/knowledge/ask                 前台智能选牌 / 知识库问答（公开）
 *   GET  /api/admin/knowledge/status        后台知识库状态（令牌）
 *   POST /api/admin/knowledge/rebuild       后台重建全部索引（令牌）
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /** 前台公开问答：向量检索 + 云端大模型回答。 */
    @PostMapping("/knowledge/ask")
    public ApiResult<KnowledgeAnswerVO> ask(@Valid @RequestBody KnowledgeAskRequest request) {
        return ApiResult.ok(knowledgeService.ask(
                request.question(), request.topK(), request.model(), request.history()));
    }

    /** 可切换的模型列表。 */
    @GetMapping("/knowledge/models")
    public ApiResult<List<KnowledgeModelVO>> models() {
        return ApiResult.ok(knowledgeService.availableModels());
    }

    /** 首次进入时的短提问建议（根据知识库中的公路 / 价格生成）。 */
    @GetMapping("/knowledge/suggestions")
    public ApiResult<List<String>> suggestions() {
        return ApiResult.ok(knowledgeService.starterSuggestions());
    }

    /** 多轮对话后的“猜你喜欢”。 */
    @PostMapping("/knowledge/guess-you-like")
    public ApiResult<GuessYouLikeVO> guessYouLike(@RequestBody GuessYouLikeRequest request) {
        return ApiResult.ok(knowledgeService.guessYouLike(
                request == null ? null : request.history(),
                request == null ? null : request.excludeIds()));
    }

    /**
     * 前台公开问答（SSE 流式）：
     * meta（参考广告牌 / 模型信息）→ delta（模型增量文本）→ done（完整结果）。
     */
    @PostMapping(value = "/knowledge/ask/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter askStream(@Valid @RequestBody KnowledgeAskRequest request,
                                HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(120_000L);
        knowledgeService.streamAsk(request.question(), request.topK(), request.model(),
                request.history(), emitter);
        return emitter;
    }

    /**
     * 图片选牌（SSE 流式）：先做本地视觉相似度比对，再交给大模型分析。
     * 上传图片只在内存中处理，不落盘。
     */
    @PostMapping(value = "/knowledge/ask/image/stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter askImageStream(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "question", required = false) String question,
                                     @RequestParam(value = "model", required = false) String model,
                                     @RequestParam(value = "topK", required = false) Integer topK,
                                     @RequestParam(value = "history", required = false) String historyJson,
                                     HttpServletResponse response) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请上传图片");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new BadRequestException("图片不能超过 10MB");
        }
        String contentType = file.getContentType();
        if (contentType == null
                || !(contentType.equalsIgnoreCase(MediaType.IMAGE_PNG_VALUE)
                || contentType.equalsIgnoreCase(MediaType.IMAGE_JPEG_VALUE))) {
            throw new BadRequestException("仅支持 PNG 或 JPEG 图片");
        }
        byte[] imageBytes;
        try {
            imageBytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("图片读取失败，请重新上传");
        }
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(120_000L);
        knowledgeService.streamImageAsk(imageBytes, contentType, question, model, topK,
                parseHistory(historyJson), emitter);
        return emitter;
    }

    private List<ChatTurn> parseHistory(String historyJson) {
        if (historyJson == null || historyJson.isBlank()) {
            return List.of();
        }
        try {
            List<ChatTurn> history = MAPPER.readValue(historyJson,
                    new TypeReference<List<ChatTurn>>() {
                    });
            return history == null ? List.of() : history;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 后台知识库索引状态。 */
    @GetMapping("/admin/knowledge/status")
    public ApiResult<KnowledgeStatusVO> status() {
        return ApiResult.ok(knowledgeService.status());
    }

    /** 后台强制重建全部索引。 */
    @PostMapping("/admin/knowledge/rebuild")
    public ApiResult<KnowledgeStatusVO> rebuild() {
        return ApiResult.ok(knowledgeService.rebuildAll());
    }
}
