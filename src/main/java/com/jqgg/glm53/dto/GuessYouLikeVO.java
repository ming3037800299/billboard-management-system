package com.jqgg.glm53.dto;

import java.util.List;

/**
 * “猜你喜欢”结果：对游客偏好的简短总结 + 推荐广告牌。
 */
public record GuessYouLikeVO(String summary, List<KnowledgeSourceVO> items) {
}
