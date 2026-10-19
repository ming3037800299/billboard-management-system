package com.jqgg.glm53.service;

import com.jqgg.glm53.entity.Billboard;
import com.jqgg.glm53.entity.BillboardStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库查询意图解析测试：价格区间 / 状态 / 排序 / 位置关键词 / 计费周期折算。
 */
class KnowledgeQueryAnalyzerTest {

    private final KnowledgeQueryAnalyzer analyzer = new KnowledgeQueryAnalyzer();

    private final List<Billboard> billboards = List.of(
            billboard("京沪高速保定南段高炮广告牌", "京沪高速G2", "保定南段 K1123+500",
                    "河北省保定市清苑区", "26000", "元/月", BillboardStatus.AVAILABLE),
            billboard("京沪高速天津静海段广告牌", "京沪高速G2", "天津静海段 K1080+600",
                    "天津市静海区", "42000", "元/月", BillboardStatus.AVAILABLE),
            billboard("G312沪霍线呼图壁段广告牌", "G312沪霍线", "呼图壁段 K78+900",
                    "新疆昌吉州呼图壁县", "17000", "元/月", BillboardStatus.AVAILABLE),
            billboard("连霍高速宝鸡段广告牌", "连霍高速G30", "宝鸡段 K860+200",
                    "陕西省宝鸡市陈仓区", "240000", "元/年", BillboardStatus.AVAILABLE));

    @Test
    void 解析月租预算和租赁状态() {
        KnowledgeQueryAnalyzer.QueryIntent intent =
                analyzer.analyze("月租 3 万以内的可租赁广告牌", billboards);

        assertThat(intent.status()).isEqualTo(BillboardStatus.AVAILABLE);
        assertThat(intent.maxMonthlyPrice()).isEqualTo(30000.0);
        assertThat(intent.minMonthlyPrice()).isNull();
        assertThat(intent.hasFilterOrSort()).isTrue();
    }

    @Test
    void 年租预算折算为月均价() {
        KnowledgeQueryAnalyzer.QueryIntent intent =
                analyzer.analyze("年租 30 万以内的可租赁广告牌", billboards);

        assertThat(intent.maxMonthlyPrice()).isEqualTo(25000.0);
    }

    @Test
    void 识别最便宜和最贵() {
        assertThat(analyzer.analyze("最便宜的广告牌是哪个", billboards).priceSort())
                .isEqualTo(KnowledgeQueryAnalyzer.PriceSort.ASC);
        assertThat(analyzer.analyze("最贵的广告牌是哪个", billboards).priceSort())
                .isEqualTo(KnowledgeQueryAnalyzer.PriceSort.DESC);
    }

    @Test
    void 识别问题中的公路关键词() {
        KnowledgeQueryAnalyzer.QueryIntent intent =
                analyzer.analyze("京沪高速 3 万以内的可租赁广告牌", billboards);

        assertThat(intent.locationKeyword()).isEqualTo("京沪高速");
    }

    @Test
    void 年付报价折算为月均价() {
        Billboard annual = billboards.get(3);
        Billboard monthly = billboards.get(0);

        assertThat(KnowledgeQueryAnalyzer.monthlyPrice(annual)).isEqualTo(20000.0);
        assertThat(KnowledgeQueryAnalyzer.monthlyPrice(monthly)).isEqualTo(26000.0);
    }

    private Billboard billboard(String name, String road, String section, String address,
                                String price, String unit, BillboardStatus status) {
        Billboard b = new Billboard();
        b.setName(name);
        b.setRoad(road);
        b.setSection(section);
        b.setAddress(address);
        b.setPrice(new BigDecimal(price));
        b.setPriceUnit(unit);
        b.setStatus(status);
        return b;
    }
}
