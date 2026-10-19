package com.jqgg.glm53.service;

import com.jqgg.glm53.entity.Billboard;
import com.jqgg.glm53.entity.BillboardStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库检索的查询意图解析。
 *
 * <p>纯向量检索适合“杭州适合汽车品牌投放吗”这类语义问题，但无法可靠处理
 * “月租 3 万以内”“最便宜”“可租赁”这类需要全量比较的结构化条件。
 * 这里把这类条件解析出来，交给 {@link KnowledgeService} 做结构化预筛选。</p>
 */
final class KnowledgeQueryAnalyzer {

    /** 金额：支持 3万 / 3w / 3千 / 3k / 30000；排除 G60、K1765 这类公路桩号数字。 */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?<![A-Za-z0-9])(\\d+(?:\\.\\d+)?)\\s*(万|w|W|千|k|K)?(?![A-Za-z0-9])");

    private static final List<String> MAX_WORDS = List.of(
            "以内", "以下", "不超过", "不高于", "低于", "少于", "最多", "封顶");
    private static final List<String> MIN_WORDS = List.of(
            "以上", "不低于", "至少", "高于", "超过");
    private static final List<String> PRICE_HINTS = List.of(
            "元", "块", "¥", "￥", "预算", "月租", "年租", "租金", "报价", "价格", "费用",
            "以内", "以下", "以上", "不超过", "不高于", "不低于", "低于", "高于", "超过", "最多", "至少");

    private static final Set<String> STOPWORDS = Set.of(
            "广告牌", "广告", "广告位", "点位", "公路", "道路", "路段", "沿线", "地区",
            "租赁", "出租", "月租", "年租", "租金", "价格", "报价", "预算",
            "以内", "以下", "以上", "不超过", "不高于", "不低于", "低于", "高于", "超过", "最多", "至少",
            "可租", "已租", "暂停", "便宜", "最便宜", "最贵", "性价比", "适合", "品牌", "投放", "附近",
            "哪些", "多少", "全部", "所有", "列出", "信息", "推荐", "一个", "可以", "需要", "帮忙",
            "查找", "查询", "有没有", "什么", "怎么", "如何", "这些", "那些", "其中", "以及", "还有",
            "的是", "是", "的", "了", "吗", "呢", "请", "帮我", "一下", "我们", "我");

    /** 查询意图；locationKeyword 为问题中真实出现在广告牌位置字段里的最长词。 */
    record QueryIntent(BillboardStatus status,
                       Double minMonthlyPrice,
                       Double maxMonthlyPrice,
                       PriceSort priceSort,
                       String locationKeyword) {

        boolean hasFilterOrSort() {
            return status != null || minMonthlyPrice != null || maxMonthlyPrice != null || priceSort != null;
        }

        boolean hasPriceFilter() {
            return minMonthlyPrice != null || maxMonthlyPrice != null;
        }

        /** 供提示词使用的条件描述。 */
        String describe() {
            List<String> parts = new ArrayList<>();
            if (status != null) {
                parts.add("状态=" + status.label());
            }
            if (maxMonthlyPrice != null) {
                parts.add("月均价格≤¥" + money(maxMonthlyPrice));
            }
            if (minMonthlyPrice != null) {
                parts.add("月均价格≥¥" + money(minMonthlyPrice));
            }
            if (priceSort == PriceSort.ASC) {
                parts.add("按价格从低到高");
            } else if (priceSort == PriceSort.DESC) {
                parts.add("按价格从高到低");
            }
            if (locationKeyword != null) {
                parts.add("位置/公路包含“" + locationKeyword + "”");
            }
            return String.join("，", parts);
        }
    }

    enum PriceSort {
        ASC, DESC
    }

    QueryIntent analyze(String question, Collection<Billboard> billboards) {
        String q = question == null ? "" : question.trim();
        String compact = q.replaceAll("\\s+", "");

        BillboardStatus status = parseStatus(compact);
        PriceSort sort = parsePriceSort(compact);
        Double min = null;
        Double max = null;

        Matcher matcher = AMOUNT.matcher(q);
        while (matcher.find()) {
            String unit = matcher.group(2);
            double amount = Double.parseDouble(matcher.group(1));
            if (unit != null) {
                amount *= switch (unit) {
                    case "万", "w", "W" -> 10000;
                    case "千", "k", "K" -> 1000;
                    default -> 1;
                };
            }
            int start = Math.max(0, matcher.start() - 12);
            int end = Math.min(q.length(), matcher.end() + 12);
            String window = q.substring(start, end);
            if (!looksLikePrice(window, unit)) {
                continue;
            }
            // “年租 30 万以内”与“月租 3 万以内”统一折算为月均价再比较。
            double monthly = isAnnual(window) ? amount / 12.0 : amount;

            if (window.contains("不低于")) {
                min = min == null ? monthly : Math.max(min, monthly);
            } else if (window.contains("不超过") || window.contains("不高于")) {
                max = max == null ? monthly : Math.min(max, monthly);
            } else if (containsAny(window, MAX_WORDS)) {
                max = max == null ? monthly : Math.min(max, monthly);
            } else if (containsAny(window, MIN_WORDS)) {
                min = min == null ? monthly : Math.max(min, monthly);
            } else if (window.contains("预算")) {
                max = max == null ? monthly : Math.min(max, monthly);
            }
        }

        return new QueryIntent(status, min, max, sort, findLocationKeyword(q, billboards));
    }

    /** 广告牌价格折算为月均价，便于不同计费周期统一比较；无报价返回 null。 */
    static Double monthlyPrice(Billboard b) {
        if (b == null || b.getPrice() == null) {
            return null;
        }
        double price = b.getPrice().doubleValue();
        String unit = b.getPriceUnit() == null ? "" : b.getPriceUnit().trim();
        if (unit.contains("半年")) {
            return price / 6.0;
        }
        if (unit.contains("年")) {
            return price / 12.0;
        }
        if (unit.contains("季")) {
            return price / 3.0;
        }
        if (unit.contains("天") || unit.contains("日")) {
            return price * 30.0;
        }
        return price;
    }

    static String money(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.005) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** 广告牌名称/公路/路段/地址中是否包含指定关键词。 */
    static boolean containsLocation(Billboard b, String keyword) {
        if (b == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        return containsIgnoreCase(b.getName(), needle)
                || containsIgnoreCase(b.getRoad(), needle)
                || containsIgnoreCase(b.getSection(), needle)
                || containsIgnoreCase(b.getAddress(), needle);
    }

    private BillboardStatus parseStatus(String q) {
        if (q.contains("已租") || q.contains("已出租")) {
            return BillboardStatus.RENTED;
        }
        if (q.contains("暂停") || q.contains("停租") || q.contains("不可租")) {
            return BillboardStatus.PAUSED;
        }
        if (q.contains("可租") || q.contains("待租") || q.contains("空置")
                || q.contains("出租") || q.contains("在租")) {
            return BillboardStatus.AVAILABLE;
        }
        return null;
    }

    private PriceSort parsePriceSort(String q) {
        if (q.contains("最贵") || q.contains("最高价") || q.contains("价格最高")
                || q.contains("价位最高") || q.contains("昂贵")) {
            return PriceSort.DESC;
        }
        if (q.contains("最便宜") || q.contains("最低价") || q.contains("价格最低")
                || q.contains("价位最低") || q.contains("最划算") || q.contains("性价比高")
                || q.contains("便宜")) {
            return PriceSort.ASC;
        }
        return null;
    }

    private boolean looksLikePrice(String window, String unit) {
        if (unit != null) {
            return true;
        }
        return containsAny(window, PRICE_HINTS);
    }

    private boolean isAnnual(String window) {
        boolean annual = window.contains("年租") || window.contains("每年") || window.contains("年付")
                || window.contains("一年") || window.contains("元/年") || window.contains("/年");
        boolean monthly = window.contains("月租") || window.contains("每月") || window.contains("月付")
                || window.contains("元/月") || window.contains("/月");
        return annual && !monthly;
    }

    /** 问题中是否包含能在广告牌位置字段中命中的地名 / 设施词。 */
    boolean hasLocationKeyword(String question, Collection<Billboard> billboards) {
        return findLocationKeyword(question, billboards) != null;
    }

    /**
     * 找出问题中最长的、且在广告牌名称/公路/路段/地址中真实出现过的词。
     * 只用于结构化筛选，避免“杭州 3 万以内”被当成全国范围的预算查询。
     */
    private String findLocationKeyword(String question, Collection<Billboard> billboards) {
        if (billboards == null || billboards.isEmpty()) {
            return null;
        }
        String cleaned = question.replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》]+", " ");
        String best = null;
        for (String segment : cleaned.split("\\s+")) {
            int maxLen = Math.min(8, segment.length());
            for (int n = maxLen; n >= 2; n--) {
                for (int i = 0; i + n <= segment.length(); i++) {
                    String candidate = segment.substring(i, i + n);
                    if (isStopword(candidate) || candidate.chars().allMatch(Character::isDigit)) {
                        continue;
                    }
                    if (matchesLocation(billboards, candidate)
                            && (best == null || candidate.length() > best.length())) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private boolean matchesLocation(Collection<Billboard> billboards, String candidate) {
        for (Billboard b : billboards) {
            if (containsLocation(b, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private boolean isStopword(String candidate) {
        return STOPWORDS.contains(candidate);
    }

    private boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
