package com.jqgg.glm53.dto;

/**
 * 多轮对话中的一条消息：role=user / assistant，content 为文本内容。
 * 前端只回传最近若干轮，用于理解“那杭州的呢”这类追问。
 */
public record ChatTurn(String role, String content) {
}
