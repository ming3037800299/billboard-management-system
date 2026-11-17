package com.jqgg.glm53.service;

import com.jqgg.glm53.entity.BillboardImageCategory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 广告牌图片自动分类。
 *
 * <p>分类依据分两层：</p>
 * <ol>
 *   <li>文件名关键词：宣传海报 / 实景照片 / 设计图纸等命名直接给出强信号；</li>
 *   <li>图片基础特征：JPEG EXIF 拍摄设备与时间、宽高比、画面留白比例、色彩饱和度。</li>
 * </ol>
 *
 * <p>规则是启发式的：命名规范、带拍摄信息的图片准确率较高；命名混乱或经过二次压缩的图片
 * 置信度会偏低，后台预览中支持人工调整分类。后续可把同一入口替换为视觉模型判断。</p>
 */
@Service
public class ImageClassificationService {

    /** 单张图片解码前的像素上限（防止压缩炸弹） */
    private static final long MAX_DECODE_PIXELS = 30_000_000L;

    /** 文件名关键词：按“首命中即加分”处理，避免一个文件名重复累加。 */
    private static final String[] PROMO_KEYWORDS = {
            "宣传", "海报", "推广", "主视觉", "主kv", "画面", "创意", "出街", "定稿", "上画",
            "广告画面", "广告图", "banner", "poster", "promo", "keyvisual", "key_visual",
            "kv", "creative", "campaign"
    };

    private static final String[] REAL_KEYWORDS = {
            "实景", "现场", "实拍", "照片", "拍摄", "实地", "外景", "全景", "航拍", "无人机",
            "photo", "photograph", "shot", "site", "scene", "real", "img_", "dsc_", "dscf",
            "pxl_", "mvc_", "camera", "wx_camera", "微信图片"
    };

    private static final String[] DESIGN_KEYWORDS = {
            "设计", "图纸", "效果图", "施工图", "平面图", "立面图", "剖面图", "结构图", "草图",
            "手绘", "方案图", "鸟瞰", "户型", "加工图", "工程图", "design", "drawing",
            "blueprint", "sketch", "draft", "render", "mockup", "cad", "psd"
    };

    /** 分类结果：category 为最终归类，confidence 为 0~1 的启发式置信度，reason 为可展示的识别依据。 */
    public record Result(BillboardImageCategory category, double confidence, String reason,
                         int width, int height, boolean cameraInfo, String format) {
    }

    /**
     * 对单张图片做分类。
     *
     * @param filename 原始文件名（含扩展名，可含目录）
     * @param bytes    图片字节（已通过魔数校验）
     */
    public Result classify(String filename, byte[] bytes) {
        String format = detectFormat(bytes);
        ImageInfo info = readImageInfo(bytes, format);
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        boolean cameraInfo = "jpg".equals(format) && hasCameraInfo(bytes);

        double promo = 0;
        double real = 0;
        double design = 0;
        List<String> promoWhy = new ArrayList<>();
        List<String> realWhy = new ArrayList<>();
        List<String> designWhy = new ArrayList<>();

        if (hit(lower, PROMO_KEYWORDS)) {
            promo += 3.2;
            promoWhy.add("文件名含宣传/海报类关键词");
        }
        if (hit(lower, REAL_KEYWORDS)) {
            real += 3.0;
            realWhy.add("文件名含实景/拍摄类关键词");
        }
        if (hit(lower, DESIGN_KEYWORDS)) {
            design += 3.2;
            designWhy.add("文件名含设计/图纸类关键词");
        }

        if (cameraInfo) {
            real += 2.4;
            realWhy.add("含拍摄设备/时间信息");
        }

        if (info.width > 0 && info.height > 0) {
            double ratio = (double) info.width / info.height;
            if (ratio >= 2.6) {
                promo += 1.6;
                promoWhy.add("横幅比例 " + fmtRatio(ratio) + "（宣传画面常见）");
            } else if (ratio >= 2.0) {
                promo += 1.2;
                promoWhy.add("横幅比例 " + fmtRatio(ratio));
            } else if (ratio >= 1.6) {
                promo += 0.6;
                promoWhy.add("宽幅比例 " + fmtRatio(ratio));
            }
            if (isA4Ratio(ratio)) {
                design += 0.6;
                designWhy.add("接近 A4 图纸比例 " + fmtRatio(ratio));
            }
        }

        if (info.image != null) {
            Stats stats = stats(info.image);
            if (stats.whiteRatio >= 0.55) {
                design += 1.2;
                designWhy.add("大面积白底/留白（" + pct(stats.whiteRatio) + "）");
            }
            if (stats.whiteRatio >= 0.7 && stats.coloredRatio < 0.2) {
                design += 0.6;
                designWhy.add("低色彩占比，符合图纸特征");
            }
            if (stats.coloredRatio < 0.16 && stats.whiteRatio >= 0.45) {
                design += 0.4;
            }
            if (stats.coloredRatio >= 0.25 && stats.whiteRatio < 0.4) {
                real += 0.5;
                realWhy.add("画面色彩分布接近实景照片");
            }
            if (stats.coloredRatio >= 0.45 && stats.whiteRatio < 0.35) {
                promo += 0.4;
                promoWhy.add("色彩饱和、画面元素集中");
            }
            if ("png".equals(format) && stats.whiteRatio >= 0.4) {
                design += 0.2;
            }
            if (info.width > 0 && info.height > 0) {
                double ratio = (double) info.width / info.height;
                if (ratio >= 1.2 && ratio <= 1.8) {
                    real += 0.2;
                }
            }
        }

        double top = Math.max(promo, Math.max(real, design));
        BillboardImageCategory category;
        List<String> why;
        if (top < 0.6) {
            // 无明显信号时按基础特征兜底，并降低置信度，提示人工复核
            if (cameraInfo) {
                category = BillboardImageCategory.REAL_PHOTO;
                why = new ArrayList<>(List.of("缺少有效命名，按拍摄信息归为实景照片"));
            } else if (info.image != null && info.whiteRatio() >= 0.55) {
                category = BillboardImageCategory.DESIGN_DRAWING;
                why = new ArrayList<>(List.of("缺少有效命名，按大面积留白归为设计图纸"));
            } else if (info.width > 0 && (double) info.width / info.height >= 1.9) {
                category = BillboardImageCategory.PROMO;
                why = new ArrayList<>(List.of("缺少有效命名，按横幅比例归为宣传图"));
            } else {
                category = BillboardImageCategory.REAL_PHOTO;
                why = new ArrayList<>(List.of("缺少有效命名与拍摄信息，默认归为实景照片"));
            }
            return new Result(category, 0.36, String.join("；", why),
                    info.width, info.height, cameraInfo, format);
        }

        if (promo >= real && promo >= design) {
            category = BillboardImageCategory.PROMO;
            why = promoWhy;
            top = promo;
        } else if (real >= design) {
            category = BillboardImageCategory.REAL_PHOTO;
            why = realWhy;
            top = real;
        } else {
            category = BillboardImageCategory.DESIGN_DRAWING;
            why = designWhy;
            top = design;
        }

        double second = secondScore(promo, real, design, category);
        if (why == null || why.isEmpty()) {
            why = new ArrayList<>(List.of("按图片尺寸与画面特征综合判断"));
        }
        double confidence = clamp(0.55 + top * 0.06 + Math.max(0, top - second) * 0.12, 0.55, 0.97);
        String reason = String.join("；", why.subList(0, Math.min(3, why.size())));
        return new Result(category, round2(confidence), reason, info.width, info.height, cameraInfo, format);
    }

    // ------------------- 图片格式与基础信息 -------------------

    /** 根据文件头识别真实格式，返回 jpg/png/gif/bmp/webp；非图片返回 null。 */
    public static String detectFormat(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        int b2 = b[2] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return "jpg";
        }
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b[3] == 0x47 && b[4] == 0x0D
                && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "png";
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "gif";
        }
        if (b[0] == 'B' && b[1] == 'M') {
            return "bmp";
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private record ImageInfo(int width, int height, BufferedImage image, double whiteRatio) {
    }

    private ImageInfo readImageInfo(byte[] bytes, String format) {
        if ("webp".equals(format)) {
            int[] size = webpSize(bytes);
            return new ImageInfo(size[0], size[1], null, 0);
        }
        BufferedImage image = null;
        int width = 0;
        int height = 0;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis, true, true);
                        width = reader.getWidth(0);
                        height = reader.getHeight(0);
                        if ((long) width * height <= MAX_DECODE_PIXELS) {
                            image = reader.read(0);
                        }
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (Exception ignored) {
            // 图片损坏或格式不常见时仅按文件名与尺寸兜底
        }
        return new ImageInfo(width, height, image, image == null ? 0 : stats(image).whiteRatio);
    }

    /** 采样计算画面特征：近白像素占比、彩色像素占比。 */
    private Stats stats(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) {
            return new Stats(0, 0);
        }
        int stepX = Math.max(1, w / 120);
        int stepY = Math.max(1, h / 120);
        long total = 0;
        long white = 0;
        long colored = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                total++;
                if (r >= 235 && g >= 235 && b >= 235) {
                    white++;
                }
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (max - min >= 40) {
                    colored++;
                }
            }
        }
        if (total == 0) {
            return new Stats(0, 0);
        }
        return new Stats((double) white / total, (double) colored / total);
    }

    private record Stats(double whiteRatio, double coloredRatio) {
    }

    // ------------------- JPEG EXIF 拍摄信息 -------------------

    /**
     * 读取 JPEG APP1 段中的 EXIF，判断是否包含拍摄设备（Make/Model）或拍摄时间。
     * 只做最小解析，不引入额外依赖。
     */
    private boolean hasCameraInfo(byte[] b) {
        try {
            int i = 2;
            while (i + 4 <= b.length) {
                if ((b[i] & 0xFF) != 0xFF) {
                    i++;
                    continue;
                }
                int marker = b[i + 1] & 0xFF;
                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                    i += 2;
                    continue;
                }
                if (marker == 0xDA || marker == 0xD9) {
                    break;
                }
                int segLen = u16(b, i + 2);
                if (segLen < 2 || i + 2 + segLen > b.length) {
                    break;
                }
                if (marker == 0xE1 && i + 10 <= b.length
                        && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i'
                        && b[i + 7] == 'f' && b[i + 8] == 0 && b[i + 9] == 0) {
                    return tiffHasCameraInfo(b, i + 10, i + 2 + segLen);
                }
                i += 2 + segLen;
            }
        } catch (RuntimeException ignored) {
            // 损坏的 EXIF 直接视为无拍摄信息
        }
        return false;
    }

    private boolean tiffHasCameraInfo(byte[] b, int tiffStart, int end) {
        if (tiffStart + 8 > end || tiffStart + 8 > b.length) {
            return false;
        }
        boolean little;
        if (b[tiffStart] == 'I' && b[tiffStart + 1] == 'I') {
            little = true;
        } else if (b[tiffStart] == 'M' && b[tiffStart + 1] == 'M') {
            little = false;
        } else {
            return false;
        }
        if (u16(b, tiffStart + 2, little) != 42) {
            return false;
        }
        long ifd0 = u32(b, tiffStart + 4, little);
        boolean[] found = new boolean[1];
        readIfd(b, tiffStart, end, ifd0, little, found, true);
        return found[0];
    }

    private void readIfd(byte[] b, int tiffStart, int end, long ifdOffset, boolean little,
                         boolean[] found, boolean allowExifPointer) {
        long pos = tiffStart + ifdOffset;
        if (pos < tiffStart || pos + 2 > end || pos + 2 > b.length) {
            return;
        }
        int count = u16(b, (int) pos, little);
        if (count < 0 || count > 512) {
            return;
        }
        for (int k = 0; k < count; k++) {
            int entry = (int) pos + 2 + k * 12;
            if (entry + 12 > b.length) {
                return;
            }
            int tag = u16(b, entry, little);
            int type = u16(b, entry + 2, little);
            long valueCount = u32(b, entry + 4, little);
            if (tag == 0x010F || tag == 0x0110 || tag == 0x9003 || tag == 0x9004 || tag == 0x0132) {
                String value = asciiValue(b, tiffStart, end, entry, type, valueCount, little);
                if (value != null && !value.isBlank() && value.length() >= 4) {
                    found[0] = true;
                    return;
                }
            }
            if (allowExifPointer && tag == 0x8769 && type == 4 && valueCount >= 1) {
                long exifIfd = u32(b, entry + 8, little);
                readIfd(b, tiffStart, end, exifIfd, little, found, false);
                if (found[0]) {
                    return;
                }
            }
        }
    }

    private String asciiValue(byte[] b, int tiffStart, int end, int entry, int type,
                              long count, boolean little) {
        if (type != 2 || count <= 0 || count > 256) {
            return null;
        }
        long valuePos = count <= 4 ? entry + 8 : tiffStart + u32(b, entry + 8, little);
        if (valuePos < 0 || valuePos + count > end || valuePos + count > b.length) {
            return null;
        }
        int len = (int) count;
        while (len > 0 && b[(int) valuePos + len - 1] == 0) {
            len--;
        }
        return new String(b, (int) valuePos, len, StandardCharsets.US_ASCII);
    }

    private int u16(byte[] b, int pos) {
        return u16(b, pos, true);
    }

    private int u16(byte[] b, int pos, boolean little) {
        if (pos < 0 || pos + 2 > b.length) {
            return 0;
        }
        int x = b[pos] & 0xFF;
        int y = b[pos + 1] & 0xFF;
        return little ? (x | (y << 8)) : ((x << 8) | y);
    }

    private long u32(byte[] b, int pos, boolean little) {
        if (pos < 0 || pos + 4 > b.length) {
            return 0;
        }
        long x = b[pos] & 0xFF;
        long y = b[pos + 1] & 0xFF;
        long z = b[pos + 2] & 0xFF;
        long w = b[pos + 3] & 0xFF;
        return little ? (x | (y << 8) | (z << 16) | (w << 24))
                : ((x << 24) | (y << 16) | (z << 8) | w);
    }

    // ------------------- WebP 尺寸（无 ImageIO 插件时） -------------------

    private int[] webpSize(byte[] b) {
        try {
            int chunk = 12;
            while (chunk + 8 <= b.length) {
                String fourcc = new String(b, chunk, 4, StandardCharsets.ISO_8859_1);
                long size = le32(b, chunk + 4);
                int data = chunk + 8;
                if ("VP8X".equals(fourcc) && data + 10 <= b.length) {
                    int w = 1 + (b[data + 4] & 0xFF) + ((b[data + 5] & 0xFF) << 8) + ((b[data + 6] & 0xFF) << 16);
                    int h = 1 + (b[data + 7] & 0xFF) + ((b[data + 8] & 0xFF) << 8) + ((b[data + 9] & 0xFF) << 16);
                    return new int[]{w, h};
                }
                if ("VP8 ".equals(fourcc) && data + 10 <= b.length) {
                    int w = ((b[data + 6] & 0xFF) | ((b[data + 7] & 0xFF) << 8)) & 0x3FFF;
                    int h = ((b[data + 8] & 0xFF) | ((b[data + 9] & 0xFF) << 8)) & 0x3FFF;
                    return new int[]{w, h};
                }
                if ("VP8L".equals(fourcc) && data + 5 <= b.length) {
                    long bits = le32(b, data + 1);
                    int w = (int) (bits & 0x3FFF) + 1;
                    int h = (int) ((bits >> 14) & 0x3FFF) + 1;
                    return new int[]{w, h};
                }
                if (size <= 0 || size > b.length) {
                    break;
                }
                chunk = data + (int) size + ((size & 1) == 1 ? 1 : 0);
            }
        } catch (RuntimeException ignored) {
            // 尺寸解析失败时按 0 处理，分类退化为文件名规则
        }
        return new int[]{0, 0};
    }

    private long le32(byte[] b, int pos) {
        if (pos < 0 || pos + 4 > b.length) {
            return 0;
        }
        return (b[pos] & 0xFFL) | ((b[pos + 1] & 0xFFL) << 8)
                | ((b[pos + 2] & 0xFFL) << 16) | ((b[pos + 3] & 0xFFL) << 24);
    }

    // ------------------- 小工具 -------------------

    private boolean hit(String filename, String[] keywords) {
        for (String keyword : keywords) {
            if (filename.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isA4Ratio(double ratio) {
        return (ratio >= 0.66 && ratio <= 0.76) || (ratio >= 1.32 && ratio <= 1.52);
    }

    private double secondScore(double promo, double real, double design, BillboardImageCategory top) {
        return switch (top) {
            case PROMO -> Math.max(real, design);
            case REAL_PHOTO -> Math.max(promo, design);
            case DESIGN_DRAWING -> Math.max(promo, real);
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private String fmtRatio(double ratio) {
        return String.format(Locale.ROOT, "%.2f", ratio);
    }

    private String pct(double ratio) {
        return Math.round(ratio * 100) + "%";
    }
}
