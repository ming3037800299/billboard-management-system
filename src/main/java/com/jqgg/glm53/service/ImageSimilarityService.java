package com.jqgg.glm53.service;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.entity.Billboard;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.repository.BillboardRepository;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地图片视觉相似度服务。
 *
 * <p>不依赖外部视觉模型：把图片缩放到统一尺寸后提取颜色直方图、aHash/dHash
 * 感知哈希和构图比例，与每条广告牌图片比对。广告牌示例图为 SVG，先用 Batik
 * 光栅化；上传图片只接受 PNG/JPEG，避免把 SVG 脚本带入处理流程。</p>
 */
@Service
public class ImageSimilarityService {

    private static final Logger log = LoggerFactory.getLogger(ImageSimilarityService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Pattern VIEW_BOX = Pattern.compile(
            "viewBox\\s*=\\s*[\"']([^\"']+)[\"']");

    private final BillboardRepository billboardRepository;
    private final AppProperties appProperties;
    private final Map<String, Optional<ImageFeature>> featureCache = new ConcurrentHashMap<>();

    public ImageSimilarityService(BillboardRepository billboardRepository,
                                  AppProperties appProperties) {
        this.billboardRepository = billboardRepository;
        this.appProperties = appProperties;
    }

    /** 只计算上传图片的视觉摘要，不与广告牌比对（普通图片识别 / 聊天用）。 */
    public Summary summarize(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BadRequestException("请上传图片");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new BadRequestException("图片不能超过 10MB");
        }
        ImageFeature target = featureOf(decodeRaster(imageBytes));
        return new Summary(
                target.orientation(),
                target.brightnessLabel(),
                target.colorfulnessLabel(),
                target.dominantColors(),
                0);
    }

    /**
     * 与全部广告牌图片比对，返回视觉相似度最高的 topK 条。
     *
     * @param imageBytes 上传图片字节（PNG/JPEG）
     * @param topK       返回条数（1-10）
     */
    public MatchResult match(byte[] imageBytes, int topK) {
        if (!isEnabled()) {
            throw new BadRequestException("知识库功能未启用");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BadRequestException("请上传图片");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new BadRequestException("图片不能超过 10MB");
        }

        BufferedImage uploaded = decodeRaster(imageBytes);
        ImageFeature target = featureOf(uploaded);

        List<Billboard> billboards = billboardRepository.findAll().stream()
                .filter(b -> !b.isDeleted())
                .toList();

        List<Match> matches = new ArrayList<>();
        int compared = 0;
        for (Billboard billboard : billboards) {
            List<String> paths = parseImages(billboard.getImages());
            Match best = null;
            for (String path : paths) {
                Optional<ImageFeature> feature = featureForPath(path);
                if (feature.isEmpty()) {
                    continue;
                }
                compared++;
                ImageFeature candidate = feature.get();
                double hashScore = hashSimilarity(target, candidate);
                double colorScore = histogramIntersection(target.histogram(), candidate.histogram());
                double aspectScore = aspectSimilarity(target, candidate);
                double score = clamp(0.45 * hashScore + 0.45 * colorScore + 0.10 * aspectScore);
                if (best == null || score > best.score()) {
                    best = new Match(billboard, path, round4(score),
                            round4(hashScore), round4(colorScore), round4(aspectScore));
                }
            }
            if (best != null) {
                matches.add(best);
            }
        }

        matches.sort(Comparator.comparingDouble(Match::score).reversed());
        int limit = Math.max(1, Math.min(10, topK));
        List<Match> top = matches.stream().limit(limit).toList();
        Summary summary = new Summary(
                target.orientation(),
                target.brightnessLabel(),
                target.colorfulnessLabel(),
                target.dominantColors(),
                compared);
        return new MatchResult(top, summary);
    }

    private boolean isEnabled() {
        return appProperties.getRag().isEnabled();
    }

    private List<String> parseImages(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> paths = MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
            return paths == null ? List.of() : paths;
        } catch (Exception e) {
            log.warn("广告牌图片列表解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    private Optional<ImageFeature> featureForPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        return featureCache.computeIfAbsent(path, p -> {
            try {
                BufferedImage image = loadImage(p);
                return image == null ? Optional.empty() : Optional.of(featureOf(image));
            } catch (Exception e) {
                log.warn("广告牌图片特征提取失败 {}：{}", p, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** 加载广告牌图片；示例 SVG 走 Batik 光栅化，上传目录位图直接读取。 */
    private BufferedImage loadImage(String path) throws Exception {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return null; // 不请求外部地址，避免 SSRF
        }
        if (path.startsWith("/uploads/")) {
            Path base = Paths.get(appProperties.getUploadDir()).toAbsolutePath().normalize();
            Path file = base.resolve(path.substring("/uploads/".length())).normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file)) {
                return null;
            }
            try (InputStream input = Files.newInputStream(file)) {
                return decodeAny(input);
            }
        }
        if (path.startsWith("/static/")) {
            return loadClasspath(path.substring(1));
        }
        if (path.startsWith("/")) {
            return loadClasspath("static" + path);
        }
        // 相对路径：优先按上传目录解释，其次按静态资源解释
        Path base = Paths.get(appProperties.getUploadDir()).toAbsolutePath().normalize();
        Path file = base.resolve(path).normalize();
        if (file.startsWith(base) && Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                return decodeAny(input);
            }
        }
        return loadClasspath("static/" + path);
    }

    private BufferedImage loadClasspath(String resourcePath) throws Exception {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream input = resource.getInputStream()) {
            return decodeAny(input);
        }
    }

    private BufferedImage decodeAny(InputStream input) throws Exception {
        byte[] bytes = input.readAllBytes();
        return decodeAny(bytes);
    }

    /**
     * 优先当位图读；读不出来再尝试 SVG 光栅化。
     * 这样示例 SVG 和用户上传的 PNG/JPEG 都能走同一套特征提取。
     */
    private BufferedImage decodeAny(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        BufferedImage raster = ImageIO.read(new ByteArrayInputStream(bytes));
        if (raster != null) {
            return raster;
        }
        return rasterizeSvg(bytes);
    }

    private BufferedImage decodeRaster(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new BadRequestException("图片格式不支持，请上传 PNG 或 JPEG 图片");
            }
            return image;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("图片解析失败，请上传 PNG 或 JPEG 图片");
        }
    }

    private BufferedImage rasterizeSvg(byte[] bytes) throws Exception {
        PNGTranscoder transcoder = new PNGTranscoder();
        float width = 320f;
        float height = 200f;
        Matcher matcher = VIEW_BOX.matcher(new String(bytes, StandardCharsets.UTF_8));
        if (matcher.find()) {
            String[] parts = matcher.group(1).trim().split("[\\s,]+");
            if (parts.length >= 4) {
                try {
                    double viewBoxWidth = Double.parseDouble(parts[2]);
                    double viewBoxHeight = Double.parseDouble(parts[3]);
                    if (viewBoxWidth > 0 && viewBoxHeight > 0) {
                        height = (float) Math.min(1024, width * viewBoxHeight / viewBoxWidth);
                    }
                } catch (NumberFormatException ignored) {
                    // 使用默认横版尺寸
                }
            }
        }
        // 同时设置宽高并保持 viewBox 比例，避免 Batik 默认把 800x500 拉成 320x400。
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, width);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, height);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            transcoder.transcode(new TranscoderInput(input), new TranscoderOutput(output));
        }
        return ImageIO.read(new ByteArrayInputStream(output.toByteArray()));
    }

    /** 统一转成白底 RGB 位图后提取特征，避免透明背景影响颜色统计。 */
    private ImageFeature featureOf(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            throw new BadRequestException("图片尺寸无效");
        }

        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();

        BufferedImage hashImage = resize(rgb, 9, 8);
        BufferedImage sample = resize(rgb, 32, 32);
        long aHash = averageHash(hashImage);
        long dHash = differenceHash(hashImage);

        int[] bins = new int[64];
        double brightnessSum = 0;
        double saturationSum = 0;
        int pixelCount = sample.getWidth() * sample.getHeight();
        for (int y = 0; y < sample.getHeight(); y++) {
            for (int x = 0; x < sample.getWidth(); x++) {
                int pixel = sample.getRGB(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                int index = (r >> 6) * 16 + (g >> 6) * 4 + (b >> 6);
                bins[index]++;
                brightnessSum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                saturationSum += max == 0 ? 0 : (max - min) / (double) max;
            }
        }
        double[] histogram = new double[64];
        for (int i = 0; i < bins.length; i++) {
            histogram[i] = bins[i] / (double) pixelCount;
        }

        double brightness = brightnessSum / pixelCount;
        double saturation = saturationSum / pixelCount;
        double aspect = width / (double) height;
        return new ImageFeature(
                aHash,
                dHash,
                histogram,
                brightness,
                saturation,
                width,
                height,
                aspect > 1.15 ? "横版" : aspect < 0.87 ? "竖版" : "方形",
                brightness < 0.32 ? "偏暗" : brightness > 0.7 ? "偏亮" : "中等",
                saturation < 0.18 ? "色彩偏淡" : saturation > 0.5 ? "色彩鲜艳" : "色彩适中",
                dominantColors(bins));
    }

    private BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = out.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return out;
    }

    private long averageHash(BufferedImage image) {
        int[] gray = new int[image.getWidth() * image.getHeight()];
        long sum = 0;
        int index = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int value = grayValue(image.getRGB(x, y));
                gray[index++] = value;
                sum += value;
            }
        }
        double average = sum / (double) gray.length;
        long hash = 0;
        for (int i = 0; i < Math.min(64, gray.length); i++) {
            if (gray[i] > average) {
                hash |= 1L << i;
            }
        }
        return hash;
    }

    private long differenceHash(BufferedImage image) {
        long hash = 0;
        int bit = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth() - 1 && bit < 64; x++) {
                int left = grayValue(image.getRGB(x, y));
                int right = grayValue(image.getRGB(x + 1, y));
                if (left > right) {
                    hash |= 1L << bit;
                }
                bit++;
            }
        }
        return hash;
    }

    private int grayValue(int pixel) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    }

    private double hashSimilarity(ImageFeature a, ImageFeature b) {
        int distance = Long.bitCount(a.aHash() ^ b.aHash())
                + Long.bitCount(a.dHash() ^ b.dHash());
        return clamp(1.0 - distance / 128.0);
    }

    private double histogramIntersection(double[] a, double[] b) {
        double intersection = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            intersection += Math.min(a[i], b[i]);
        }
        return clamp(intersection);
    }

    private double aspectSimilarity(ImageFeature a, ImageFeature b) {
        double ratio = Math.max(0.01, a.aspect()) / Math.max(0.01, b.aspect());
        double distance = Math.abs(Math.log(ratio)) / Math.log(4.0);
        return clamp(1.0 - Math.min(1.0, distance));
    }

    private List<String> dominantColors(int[] bins) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] > 0) {
                order.add(i);
            }
        }
        order.sort(Comparator.comparingInt((Integer i) -> bins[i]).reversed());

        List<String> colors = new ArrayList<>();
        List<int[]> selected = new ArrayList<>();
        for (Integer index : order) {
            int r = ((index >> 4) & 0x3) * 64 + 32;
            int g = ((index >> 2) & 0x3) * 64 + 32;
            int b = (index & 0x3) * 64 + 32;
            boolean tooClose = false;
            for (int[] existing : selected) {
                if (distance(r, g, b, existing[0], existing[1], existing[2]) < 55) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }
            selected.add(new int[]{r, g, b});
            colors.add(colorName(r, g, b));
            if (colors.size() >= 3) {
                break;
            }
        }
        if (colors.isEmpty()) {
            colors.add("色彩不明确");
        }
        return colors;
    }

    private int distance(int r1, int g1, int b1, int r2, int g2, int b2) {
        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;
        return (int) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private String colorName(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int delta = max - min;
        String name;
        if (max < 45) {
            name = "黑色";
        } else if (min > 220) {
            name = "白色";
        } else if (delta < 25) {
            name = "灰色";
        } else {
            float hue = Color.RGBtoHSB(r, g, b, null)[0] * 360f;
            if (hue < 15 || hue >= 345) {
                name = "红色";
            } else if (hue < 40) {
                name = "橙色";
            } else if (hue < 70) {
                name = "黄色";
            } else if (hue < 170) {
                name = "绿色";
            } else if (hue < 200) {
                name = "青色";
            } else if (hue < 260) {
                name = "蓝色";
            } else if (hue < 315) {
                name = "紫色";
            } else {
                name = "粉色";
            }
        }
        return name + " #" + String.format("%02x%02x%02x", r, g, b);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private double round4(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    /** 单张图片的视觉特征。 */
    private record ImageFeature(long aHash,
                                long dHash,
                                double[] histogram,
                                double brightness,
                                double saturation,
                                int width,
                                int height,
                                String orientation,
                                String brightnessLabel,
                                String colorfulnessLabel,
                                List<String> dominantColors) {

        double aspect() {
            return width / (double) height;
        }
    }

    /** 视觉相似命中：广告牌 + 图片路径 + 综合/分项相似度。 */
    public record Match(Billboard billboard,
                        String imagePath,
                        double score,
                        double hashScore,
                        double colorScore,
                        double aspectScore) {
    }

    /** 上传图片的视觉摘要。 */
    public record Summary(String orientation,
                          String brightness,
                          String colorfulness,
                          List<String> dominantColors,
                          int comparedCount) {
    }

    public record MatchResult(List<Match> matches, Summary summary) {
    }
}
