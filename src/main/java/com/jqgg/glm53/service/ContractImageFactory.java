package com.jqgg.glm53.service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 合同用图片生成：示例甲方签章、待签署签名占位图。
 * 所有图片均为透明背景 PNG，便于直接嵌入 Word。
 */
public final class ContractImageFactory {

    private ContractImageFactory() {
    }

    /** 生成示例甲方签章（圆形红章 + 公司名 + 合同专用章）。 */
    public static byte[] sampleSeal(String companyName) {
        int size = 320;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color red = new Color(198, 32, 38, 225);
            g.setColor(red);
            g.setStroke(new BasicStroke(7f));
            g.drawOval(14, 14, size - 28, size - 28);
            g.setStroke(new BasicStroke(2.5f));
            g.drawOval(30, 30, size - 60, size - 60);

            String name = companyName == null || companyName.isBlank()
                    ? "示例广告牌运营有限公司" : companyName.trim();
            g.setFont(new Font("SimSun", Font.BOLD, 20));
            drawCentered(g, name, size / 2, 92, size - 70);

            g.setFont(new Font("SimSun", Font.BOLD, 34));
            drawCentered(g, "合同专用章", size / 2, 175, size - 80);

            g.setFont(new Font("SimSun", Font.PLAIN, 17));
            drawCentered(g, "示例签章 · 请替换", size / 2, 226, size - 80);
        } finally {
            g.dispose();
        }
        return toPng(image);
    }

    /** 生成草稿中的“待乙方签署”占位图。 */
    public static byte[] signaturePlaceholder() {
        int width = 360;
        int height = 120;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(160, 160, 160, 180));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                    1.0f, new float[]{6f, 5f}, 0f));
            g.drawRoundRect(2, 2, width - 4, height - 4, 12, 12);
            g.setFont(new Font("SimSun", Font.PLAIN, 24));
            drawCentered(g, "待乙方签署", width / 2, height / 2 + 9, width - 16);
        } finally {
            g.dispose();
        }
        return toPng(image);
    }

    /** 生成“未配置签章”占位图（正常流程会要求先配置签章）。 */
    public static byte[] sealPlaceholder() {
        int width = 260;
        int height = 120;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(180, 180, 180, 190));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                    1.0f, new float[]{6f, 5f}, 0f));
            g.drawRoundRect(2, 2, width - 4, height - 4, 12, 12);
            g.setFont(new Font("SimSun", Font.PLAIN, 22));
            drawCentered(g, "未配置甲方签章", width / 2, height / 2 + 8, width - 16);
        } finally {
            g.dispose();
        }
        return toPng(image);
    }

    private static void drawCentered(Graphics2D g, String text, int centerX, int baselineY, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        String value = text;
        if (fm.stringWidth(value) > maxWidth) {
            while (value.length() > 1 && fm.stringWidth(value + "…") > maxWidth) {
                value = value.substring(0, value.length() - 1);
            }
            value += "…";
        }
        g.drawString(value, centerX - fm.stringWidth(value) / 2, baselineY);
    }

    private static byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("生成合同图片失败", e);
        }
    }
}
