package com.jqgg.glm53.service;

import com.jqgg.glm53.dto.ImportedImageVO;
import com.jqgg.glm53.dto.SkippedArchiveFileVO;
import com.jqgg.glm53.dto.ZipImportResultVO;
import com.jqgg.glm53.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 广告牌图片压缩包批量导入。
 *
 * <p>安全与数量约束：</p>
 * <ul>
 *   <li>仅接受 zip 且压缩包 ≤ 100MB；</li>
 *   <li>单张图片 ≤ 10MB、一次最多 200 张、解压总量 ≤ 300MB；</li>
 *   <li>按文件头校验真实图片类型，非图片（txt/docx/pdf 等）直接过滤；</li>
 *   <li>忽略 __MACOSX、隐藏文件、Thumbs.db 等系统文件；</li>
 *   <li>拒绝绝对路径与包含 “..” 的条目，防止 Zip Slip；多级目录正常图片会被扁平化导入；</li>
 *   <li>文件名按 UTF-8 解码，出现乱码时自动按 GBK 重新打开（兼容 Windows 中文压缩包）。</li>
 * </ul>
 *
 * <p>导入的图片先存入 uploads/imports/{会话}/ 临时目录，返回分类结果供后台分组预览；
 * 保存广告牌时再转存到常规年月目录。</p>
 */
@Service
public class BillboardArchiveImportService {

    private static final Logger log = LoggerFactory.getLogger(BillboardArchiveImportService.class);

    /** 压缩包大小上限 */
    private static final long MAX_ARCHIVE_BYTES = 100L * 1024 * 1024;

    /** 单次导入的图片数量上限 */
    private static final int MAX_IMAGES = 200;

    /** 压缩包条目数量上限（含非图片） */
    private static final int MAX_ENTRIES = 1000;

    /** 单次导入解压后的总字节上限 */
    private static final long MAX_TOTAL_BYTES = 300L * 1024 * 1024;

    /** 跳过明细最多返回的条数，避免异常压缩包导致响应过大 */
    private static final int MAX_SKIPPED_DETAILS = 100;

    private static final String IMPORT_SUBDIR = FileStorageService.IMPORT_DIR;

    private final FileStorageService fileStorageService;
    private final ImageClassificationService classificationService;

    public BillboardArchiveImportService(FileStorageService fileStorageService,
                                         ImageClassificationService classificationService) {
        this.fileStorageService = fileStorageService;
        this.classificationService = classificationService;
    }

    /** 导入压缩包：解压、过滤、分类、落盘，返回可预览的结果。 */
    public ZipImportResultVO importZip(MultipartFile archive) {
        if (archive == null || archive.isEmpty()) {
            throw new BadRequestException("请选择要上传的压缩包");
        }
        if (archive.getSize() > MAX_ARCHIVE_BYTES) {
            throw new BadRequestException("压缩包超过 100MB 上限，请拆分后重试");
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Path temp = null;
        boolean success = false;
        try {
            temp = Files.createTempFile("jqgg-zip-", ".zip");
            archive.transferTo(temp);
            if (!looksLikeZip(temp)) {
                throw new BadRequestException("文件不是有效的 zip 压缩包");
            }

            List<ImportedImageVO> images = new ArrayList<>();
            List<SkippedArchiveFileVO> skipped = new ArrayList<>();
            int[] skippedCount = {0};
            int totalEntries = 0;
            long totalBytes = 0;

            try (ZipFile zip = openZip(temp)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    totalEntries++;
                    if (totalEntries > MAX_ENTRIES) {
                        addSkipped(skipped, skippedCount, "（其余条目）", "压缩包条目数超过上限 " + MAX_ENTRIES);
                        break;
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String rawName = entry.getName();
                    String baseName = baseName(rawName);
                    if (shouldIgnore(rawName, baseName)) {
                        continue;
                    }
                    if (isUnsafePath(rawName)) {
                        addSkipped(skipped, skippedCount, rawName, "非法路径，已忽略");
                        continue;
                    }
                    if (images.size() >= MAX_IMAGES) {
                        addSkipped(skipped, skippedCount, rawName, "超过单次 " + MAX_IMAGES + " 张上限");
                        continue;
                    }
                    if (entry.getSize() > FileStorageService.MAX_IMAGE_BYTES) {
                        addSkipped(skipped, skippedCount, rawName, "单张超过 10MB");
                        continue;
                    }
                    if (totalBytes >= MAX_TOTAL_BYTES) {
                        addSkipped(skipped, skippedCount, rawName, "解压总量超过上限，已停止导入");
                        continue;
                    }

                    byte[] bytes;
                    try (InputStream in = zip.getInputStream(entry)) {
                        bytes = readLimited(in, FileStorageService.MAX_IMAGE_BYTES);
                    } catch (TooLargeException e) {
                        addSkipped(skipped, skippedCount, rawName, "单张超过 10MB");
                        continue;
                    } catch (IOException e) {
                        addSkipped(skipped, skippedCount, rawName, "文件读取失败");
                        continue;
                    }
                    totalBytes += bytes.length;

                    String format = ImageClassificationService.detectFormat(bytes);
                    if (format == null) {
                        addSkipped(skipped, skippedCount, rawName, "非图片文件（仅支持 jpg/png/gif/bmp/webp）");
                        continue;
                    }

                    ImageClassificationService.Result result =
                            classificationService.classify(baseName, bytes);
                    String url = fileStorageService.storeBytes(bytes, format,
                            IMPORT_SUBDIR + "/" + sessionId);
                    images.add(new ImportedImageVO(
                            url,
                            baseName,
                            result.category().name(),
                            result.category().label(),
                            result.width(),
                            result.height(),
                            images.isEmpty(),
                            result.confidence(),
                            result.reason(),
                            result.cameraInfo()));
                }
            }

            if (images.isEmpty()) {
                throw new BadRequestException("压缩包内未找到可用图片（支持 jpg/png/gif/bmp/webp）");
            }
            success = true;
            return new ZipImportResultVO(sessionId,
                    archive.getOriginalFilename(),
                    totalEntries,
                    images.size(),
                    skippedCount[0],
                    images,
                    skipped);
        } catch (BadRequestException e) {
            throw e;
        } catch (IOException e) {
            log.error("压缩包导入失败：{}", archive.getOriginalFilename(), e);
            throw new BadRequestException("压缩包读取失败，请确认文件完整后重试");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("删除临时压缩包失败：{}", temp, e);
                }
            }
            if (!success) {
                // 导入失败时清理本次已解压的临时图片
                fileStorageService.cleanupImportSession(sessionId);
            }
        }
    }

    /** 删除未保存的导入会话临时目录。 */
    public void cleanup(String sessionId) {
        fileStorageService.cleanupImportSession(sessionId);
    }

    /**
     * 直接选择多张图片时走同一套校验 / 分类 / 落盘逻辑，
     * 返回结构与压缩包导入一致，前端可统一按分类分组展示。
     */
    public ZipImportResultVO importFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("请选择要上传的图片");
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        List<ImportedImageVO> images = new ArrayList<>();
        List<SkippedArchiveFileVO> skipped = new ArrayList<>();
        int[] skippedCount = {0};
        long totalBytes = 0;
        boolean success = false;
        try {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String name = file.getOriginalFilename() == null ? "未命名图片" : file.getOriginalFilename();
                if (images.size() >= MAX_IMAGES) {
                    addSkipped(skipped, skippedCount, name, "超过单次 " + MAX_IMAGES + " 张上限");
                    continue;
                }
                if (file.getSize() > FileStorageService.MAX_IMAGE_BYTES) {
                    addSkipped(skipped, skippedCount, name, "单张超过 10MB");
                    continue;
                }
                if (totalBytes >= MAX_TOTAL_BYTES) {
                    addSkipped(skipped, skippedCount, name, "上传总量超过上限");
                    continue;
                }
                byte[] bytes;
                try (InputStream in = file.getInputStream()) {
                    bytes = readLimited(in, FileStorageService.MAX_IMAGE_BYTES);
                } catch (TooLargeException e) {
                    addSkipped(skipped, skippedCount, name, "单张超过 10MB");
                    continue;
                } catch (IOException e) {
                    addSkipped(skipped, skippedCount, name, "文件读取失败");
                    continue;
                }
                totalBytes += bytes.length;
                String format = ImageClassificationService.detectFormat(bytes);
                if (format == null) {
                    addSkipped(skipped, skippedCount, name, "非图片文件（仅支持 jpg/png/gif/bmp/webp）");
                    continue;
                }
                String baseName = baseName(name);
                ImageClassificationService.Result result = classificationService.classify(baseName, bytes);
                String url = fileStorageService.storeBytes(bytes, format,
                        IMPORT_SUBDIR + "/" + sessionId);
                images.add(new ImportedImageVO(url, baseName, result.category().name(),
                        result.category().label(), result.width(), result.height(),
                        images.isEmpty(), result.confidence(), result.reason(), result.cameraInfo()));
            }
            if (images.isEmpty()) {
                throw new BadRequestException("没有可用的图片（支持 jpg/png/gif/bmp/webp，单张 ≤ 10MB）");
            }
            success = true;
            return new ZipImportResultVO(sessionId, null, files.size(), images.size(),
                    skippedCount[0], images, skipped);
        } catch (BadRequestException e) {
            throw e;
        } finally {
            if (!success) {
                fileStorageService.cleanupImportSession(sessionId);
            }
        }
    }

    // ------------------- zip 读取 -------------------

    /** 先按 UTF-8 打开；文件名出现替换符说明是 GBK 中文压缩包，自动改用 GBK 重开。 */
    private ZipFile openZip(Path file) throws IOException {
        ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8);
        boolean garbled = false;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int checked = 0;
        while (entries.hasMoreElements() && checked < 50) {
            String name = entries.nextElement().getName();
            if (name.indexOf('\uFFFD') >= 0) {
                garbled = true;
                break;
            }
            checked++;
        }
        if (!garbled) {
            return zip;
        }
        zip.close();
        return new ZipFile(file.toFile(), Charset.forName("GBK"));
    }

    private boolean looksLikeZip(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length >= 2 && head[0] == 'P' && head[1] == 'K';
        }
    }

    /** 只保留压缩包内的文件名（多级目录扁平化），统一分隔符。 */
    private String baseName(String rawName) {
        String normalized = rawName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private boolean shouldIgnore(String rawName, String baseName) {
        String normalized = rawName.replace('\\', '/');
        if (baseName == null || baseName.isBlank() || baseName.startsWith(".")) {
            return true;
        }
        String lower = baseName.toLowerCase(Locale.ROOT);
        if ("thumbs.db".equals(lower) || "desktop.ini".equals(lower)) {
            return true;
        }
        return normalized.startsWith("__MACOSX/") || normalized.contains("/__MACOSX/");
    }

    private boolean isUnsafePath(String rawName) {
        String normalized = rawName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                || normalized.indexOf('\u0000') >= 0) {
            return true;
        }
        for (String part : normalized.split("/")) {
            if ("..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    private byte[] readLimited(InputStream in, long max) throws IOException, TooLargeException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            total += n;
            if (total > max) {
                throw new TooLargeException();
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private void addSkipped(List<SkippedArchiveFileVO> skipped, int[] counter, String name, String reason) {
        counter[0]++;
        if (skipped.size() < MAX_SKIPPED_DETAILS) {
            String display = name == null ? "" : (name.length() > 180 ? name.substring(0, 180) + "…" : name);
            skipped.add(new SkippedArchiveFileVO(display, reason));
        }
    }

    private static class TooLargeException extends Exception {
    }
}
