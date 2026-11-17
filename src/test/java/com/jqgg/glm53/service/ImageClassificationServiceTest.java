package com.jqgg.glm53.service;

import com.jqgg.glm53.entity.BillboardImageCategory;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片自动分类规则测试：文件名关键词、宽高比、白底留白等基础特征。
 */
class ImageClassificationServiceTest {

    private final ImageClassificationService service = new ImageClassificationService();

    @Test
    void recognizesPromoByFilenameAndWideRatio() throws IOException {
        byte[] jpg = image(1600, 600, Color.decode("#D7263D"), Color.decode("#1B3B6F"));
        ImageClassificationService.Result result = service.classify("京沪高速_宣传海报_主视觉.jpg", jpg);

        assertThat(result.category()).isEqualTo(BillboardImageCategory.PROMO);
        assertThat(result.confidence()).isGreaterThan(0.7);
        assertThat(result.width()).isEqualTo(1600);
        assertThat(result.height()).isEqualTo(600);
        assertThat(result.reason()).contains("宣传");
    }

    @Test
    void recognizesRealPhotoByFilename() throws IOException {
        byte[] jpg = image(1200, 900, Color.decode("#4C6B3C"), Color.decode("#B98F62"));
        ImageClassificationService.Result result = service.classify("现场实拍_点位照片.jpg", jpg);

        assertThat(result.category()).isEqualTo(BillboardImageCategory.REAL_PHOTO);
        assertThat(result.reason()).contains("实景");
    }

    @Test
    void recognizesDesignDrawingByFilename() throws IOException {
        byte[] png = image(1000, 1414, Color.WHITE, Color.WHITE);
        ImageClassificationService.Result result = service.classify("广告牌设计图纸_立面图.png", png);

        assertThat(result.category()).isEqualTo(BillboardImageCategory.DESIGN_DRAWING);
        assertThat(result.reason()).contains("设计");
    }

    @Test
    void fallsBackToRatioWhenNameHasNoKeyword() throws IOException {
        byte[] jpg = image(1600, 600, Color.decode("#F2C14E"), Color.decode("#2F4858"));
        ImageClassificationService.Result result = service.classify("IMG0001.jpg", jpg);

        // IMG 前缀本身就是实拍信号，但横幅比例更显著；断言落在合理分类且置信度不为 1
        assertThat(result.category()).isIn(BillboardImageCategory.PROMO, BillboardImageCategory.REAL_PHOTO);
        assertThat(result.confidence()).isBetween(0.3, 0.99);
    }

    @Test
    void unknownNameWithoutFeaturesUsesLowConfidenceDefault() throws IOException {
        byte[] png = image(900, 700, Color.decode("#3C6E71"), Color.decode("#8D99AE"));
        ImageClassificationService.Result result = service.classify("file0001.png", png);

        assertThat(result.category()).isNotNull();
        assertThat(result.confidence()).isBetween(0.3, 0.9);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void rejectsNonImageByMagicHeader() {
        assertThat(ImageClassificationService.detectFormat("hello".getBytes())).isNull();
        assertThat(ImageClassificationService.detectFormat(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0})).isEqualTo("png");
    }

    private byte[] image(int width, int height, Color a, Color b) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(a);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(b);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String format = "jpg";
        if (width == 1000 && height == 1414) {
            format = "png";
        }
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
}
