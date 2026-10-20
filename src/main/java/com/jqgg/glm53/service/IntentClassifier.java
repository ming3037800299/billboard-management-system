package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.ChatTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 判断用户是在咨询广告牌选牌业务，还是普通聊天。
 *
 * <p>先用关键词做快速路由，命中不了再让模型做一次极短的语义分类；
 * 普通聊天不会进入知识库检索，也不会返回参考广告牌。</p>
 */
@Service
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private static final List<String> BILLBOARD_TERMS = List.of(
            "广告牌", "广告位", "选牌", "点位", "租赁", "出租", "报价", "价格", "预算",
            "费用", "租金", "高速", "公路", "路段", "服务区", "投放", "品牌", "汽车品牌",
            "尺寸", "面积", "车流", "受众", "推荐", "相似", "适合", "多少钱", "元/月", "租");

    private static final List<String> CHAT_TERMS = List.of(
            "你好", "您好", "嗨", "hi", "hello", "谢谢", "感谢", "再见", "你是谁",
            "你叫什么", "你能做什么", "你会什么", "怎么用", "使用说明", "天气", "笑话",
            "聊天", "随便聊", "在吗", "早上好", "晚上好", "晚安", "好的", "嗯");

    private static final List<String> IMAGE_RECOMMEND_TERMS = List.of(
            "推荐", "相似", "适合", "选牌", "投放", "广告位", "广告牌", "匹配",
            "租赁", "报价", "价格", "点位", "哪块", "哪个好", "投什么", "多少钱", "租");

    private static final List<String> IMAGE_UNDERSTAND_TERMS = List.of(
            "识别", "文字", "写了什么", "内容", "描述", "画面", "物体", "品牌",
            "是什么", "看懂", "理解", "翻译", "图中", "图片里");

    private static final List<String> FOLLOW_UP_TERMS = List.of(
            "那", "这个", "它", "还有", "再", "呢", "刚才", "上面", "换成",
            "比较", "其中", "哪个", "便宜", "贵", "多少");

    private static final String CLASSIFIER_SYSTEM = """
            你是公路广告牌租赁系统的意图分类器。判断用户最新一句话是在咨询广告牌业务，还是普通聊天。
            只输出一个词：BILLBOARD 或 CHAT。
            BILLBOARD：想找 / 比较 / 推荐广告牌，询问点位、价格、预算、租赁状态、投放建议，
            或希望分析图片与广告牌的相似度、选牌。
            CHAT：问候、闲聊、问身份 / 能力 / 天气 / 一般知识，或只想识别图片文字、描述图片，
            不要求推荐广告牌。
            """;

    private final ChatCompletionService chatService;

    public IntentClassifier(ChatCompletionService chatService) {
        this.chatService = chatService;
    }

    public UserIntent classify(String question, List<ChatTurn> history, boolean hasImage) {
        String q = question == null ? "" : question.trim();
        if (hasImage) {
            return classifyImage(q, history);
        }
        if (q.isEmpty()) {
            return UserIntent.CHAT;
        }
        if (containsAny(q, BILLBOARD_TERMS)) {
            return UserIntent.BILLBOARD;
        }
        if (isFollowUp(q) && historyHasBillboard(history)) {
            return UserIntent.BILLBOARD;
        }
        if (containsAny(q, CHAT_TERMS)) {
            return UserIntent.CHAT;
        }
        return classifyWithModel(q, history, false);
    }

    private UserIntent classifyImage(String question, List<ChatTurn> history) {
        if (question.isEmpty()) {
            return UserIntent.BILLBOARD;
        }
        if (containsAny(question, IMAGE_RECOMMEND_TERMS)) {
            return UserIntent.BILLBOARD;
        }
        if (containsAny(question, IMAGE_UNDERSTAND_TERMS)) {
            return UserIntent.CHAT;
        }
        if (containsAny(question, CHAT_TERMS)) {
            return UserIntent.CHAT;
        }
        return classifyWithModel(question, history, true);
    }

    private UserIntent classifyWithModel(String question, List<ChatTurn> history, boolean hasImage) {
        try {
            StringBuilder prompt = new StringBuilder();
            String historyText = historyText(history);
            if (!historyText.isBlank()) {
                prompt.append("【对话历史】\n").append(historyText).append('\n');
            }
            prompt.append("【是否上传图片】").append(hasImage ? "是" : "否").append('\n');
            prompt.append("【最新问题】\n").append(question);
            String result = chatService.complete(
                    CLASSIFIER_SYSTEM, prompt.toString(), chatService.defaultModel(), 8);
            String upper = result == null ? "" : result.toUpperCase(Locale.ROOT);
            if (upper.contains("BILLBOARD")) {
                return UserIntent.BILLBOARD;
            }
            if (upper.contains("CHAT")) {
                return UserIntent.CHAT;
            }
        } catch (Exception e) {
            log.debug("意图分类调用失败，使用本地规则兜底：{}", e.getMessage());
        }
        if (!hasImage && isFollowUp(question) && historyHasBillboard(history)) {
            return UserIntent.BILLBOARD;
        }
        if (containsAny(question, CHAT_TERMS)) {
            return UserIntent.CHAT;
        }
        return hasImage ? UserIntent.CHAT : UserIntent.BILLBOARD;
    }

    private String historyText(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        int from = Math.max(0, history.size() - 4);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            ChatTurn turn = history.get(i);
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String content = turn.content().trim();
            if (content.length() > 300) {
                content = content.substring(0, 300) + "…";
            }
            sb.append("user".equals(turn.role()) ? "用户：" : "助手：")
                    .append(content).append('\n');
        }
        return sb.toString();
    }

    private boolean historyHasBillboard(List<ChatTurn> history) {
        if (history == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (ChatTurn turn : history) {
            if (turn != null && turn.content() != null) {
                sb.append(turn.content()).append('\n');
            }
        }
        return containsAny(sb.toString(), BILLBOARD_TERMS);
    }

    private boolean isFollowUp(String question) {
        return containsAny(question, FOLLOW_UP_TERMS);
    }

    private boolean containsAny(String text, List<String> terms) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (lower.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
