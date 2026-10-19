package com.jqgg.glm53.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 知识库问答请求体：
 * { "question": "...", "topK": 5, "model": "deepseek-chat",
 *   "history": [{"role":"user","content":"..."}, {"role":"assistant","content":"..."}] }
 */
public record KnowledgeAskRequest(
        @NotBlank(message = "请输入问题")
        @Size(max = 500, message = "问题不能超过 500 字")
        String question,

        Integer topK,

        String model,

        List<ChatTurn> history) {
}
