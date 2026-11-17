package com.jqgg.glm53.service;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.dto.ImportedImageVO;
import com.jqgg.glm53.dto.ZipImportResultVO;
import com.jqgg.glm53.entity.BillboardImageCategory;
import com.jqgg.glm53.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 压缩包批量导入测试：多级目录、非图片过滤、Zip Slip 防护、自动分类与临时目录清理。
 */
class BillboardArchiveImportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void importsImagesFromNestedDirsAndFiltersNonImages() throws IOException {
        BillboardArchiveImportService service = service();
        byte[] zip = zip(
                entry("广告牌素材/宣传图/宣传海报_主视觉.jpg", jpeg(1600, 600, Color.decode("#D7263D"))),
                entry("广告牌素材/实景/现场实拍照片.jpg", jpeg(1200, 900, Color.decode("#4C6B3C"))),
                entry("广告牌素材/图纸/设计图纸_立面图.png", png(1000, 1414, Color.WHITE)),
                entry("readme.txt", "not an image".getBytes(StandardCharsets.UTF_8)),
                entry("../evil.jpg", jpeg(400, 300, Color.BLUE)),
                entry("__MACOSX/._宣传海报.jpg", jpeg(100, 100, Color.RED)));

        ZipImportResultVO result = service.importZip(
                new MockMultipartFile("file", "素材.zip", "application/zip", zip));

        assertThat(result.accepted()).isEqualTo(3);
        assertThat(result.sessionId()).matches("^[a-f0-9]{32}$");
        assertThat(result.skippedCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.skipped()).anyMatch(s -> s.name().contains("readme.txt"));
        assertThat(result.skipped()).anyMatch(s -> s.name().contains("../evil.jpg"));

        List<String> categories = result.images().stream().map(ImportedImageVO::category).toList();
        assertThat(categories).containsExactly(
                BillboardImageCategory.PROMO.name(),
                BillboardImageCategory.REAL_PHOTO.name(),
                BillboardImageCategory.DESIGN_DRAWING.name());
        assertThat(result.images().get(0).cover()).isTrue();
        assertThat(result.images()).allMatch(img -> img.width() > 0 && img.height() > 0);
        assertThat(result.images()).allMatch(img -> img.reason() != null && !img.reason().isBlank());

        Path importDir = tempDir.resolve("imports").resolve(result.sessionId());
        try (var files = Files.list(importDir)) {
            assertThat(files.count()).isEqualTo(3);
        }
        // Zip Slip：临时目录之外不得写出文件
        assertThat(Files.exists(tempDir.resolve("evil.jpg"))).isFalse();
        assertThat(Files.exists(tempDir.getParent().resolve("evil.jpg"))).isFalse();

        service.cleanup(result.sessionId());
        assertThat(Files.exists(importDir)).isFalse();
    }

    @Test
    void rejectsNonZipFile() {
        BillboardArchiveImportService service = service();
        MockMultipartFile file = new MockMultipartFile("file", "fake.zip", "application/zip",
                "not a zip".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.importZip(file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("zip");
    }

    @Test
    void rejectsArchiveWithoutImages() throws IOException {
        BillboardArchiveImportService service = service();
        byte[] zip = zip(entry("a.txt", "text".getBytes(StandardCharsets.UTF_8)));
        MockMultipartFile file = new MockMultipartFile("file", "empty.zip", "application/zip", zip);

        assertThatThrownBy(() -> service.importZip(file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("未找到可用图片");
    }

    private BillboardArchiveImportService service() {
        AppProperties props = new AppProperties();
        props.setUploadDir(tempDir.toString());
        FileStorageService storage = new FileStorageService(props);
        return new BillboardArchiveImportService(storage, new ImageClassificationService());
    }

    private record ZipItem(String name, byte[] bytes) {
    }

    private ZipItem entry(String name, byte[] bytes) {
        return new ZipItem(name, bytes);
    }

    private byte[] zip(ZipItem... items) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (ZipItem item : items) {
                zos.putNextEntry(new ZipEntry(item.name()));
                zos.write(item.bytes());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private byte[] jpeg(int width, int height, Color color) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.setColor(color.brighter());
        g.fillRect(0, 0, width / 3, height / 3);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.drawRect(10, 10, width - 20, height - 20);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
