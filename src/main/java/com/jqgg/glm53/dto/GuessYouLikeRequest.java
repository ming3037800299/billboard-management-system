package com.jqgg.glm53.dto;

import java.util.List;

/**
 * “猜你喜欢”请求：携带最近对话与需要排除的广告牌（当前回答已展示的参考项）。
 */
public record GuessYouLikeRequest(List<ChatTurn> history, List<Long> excludeIds) {
}
