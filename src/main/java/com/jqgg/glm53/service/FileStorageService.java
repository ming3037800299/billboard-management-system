package com.jqgg.glm53.service;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 图片文件存储服务。
 * 上传的图片按"年月"子目录存放于应用配置的上传目录（默认 uploads/），
 * 文件名使用 UUID 防重名与路径穿越；仅接受白名单内的图片扩展名。
 * 压缩包导入的文件先落在 uploads/imports/{会话}/，保存广告牌时由 {@link #adopt(String)}
 * 转存到常规年月目录，避免临时会话目录被清理后影响已保存数据。
 * 初版约定：软删除 / 编辑移除图片时保留物理文件，不做清理。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** 允许上传的图片扩展名白名单 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "svg");

    /** 单张图片大小上限 10MB（与前端提示、压缩包内单图限制保持一致） */
    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /** 压缩包导入的临时目录前缀（相对上传目录） */
    public static final String IMPORT_DIR = "imports";

    /** 上传目录映射的访问前缀（见 WebConfig 资源映射） */
    private static final String URL_PREFIX = "/uploads/";

    private final AppProperties appProperties;

    public FileStorageService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 保存一批上传图片，返回可被前端直接访问的 URL 列表（如 /uploads/202609/xxx.jpg）。
     * 空列表会被跳过；类型不合法或写盘失败会抛出业务异常。
     */
    public List<String> store(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (file.getSize() > MAX_IMAGE_BYTES) {
                throw new BadRequestException("图片超过 10MB 上限：" + file.getOriginalFilename());
            }
            urls.add(storeOne(file));
        }
        return urls;
    }

    /** 保存单张图片并返回其访问 URL。 */
    private String storeOne(MultipartFile file) {
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BadRequestException("不支持的图片格式：" + file.getOriginalFilename()
                    + "（仅支持 jpg/jpeg/png/gif/webp/svg）");
        }
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readNBytes((int) MAX_IMAGE_BYTES + 1);
        } catch (IOException e) {
            log.error("图片读取失败：{}", file.getOriginalFilename(), e);
            throw new BadRequestException("图片读取失败，请重试");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new BadRequestException("图片超过 10MB 上限：" + file.getOriginalFilename());
        }
        return storeBytes(bytes, ext, monthDir());
    }

    /**
     * 保存字节内容到指定子目录（相对上传目录），返回访问 URL。
     * 子目录 / 扩展名均做安全校验，供压缩包导入与常规上传复用。
     */
    public String storeBytes(byte[] bytes, String ext, String subdir) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("图片内容为空");
        }
        if (ext == null || !ALLOWED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("不支持的图片格式：" + ext);
        }
        String safeSubdir = subdir == null || subdir.isBlank() ? monthDir() : subdir.replace('\\', '/');
        String filename = UUID.randomUUID().toString().replace("-", "")
                + "." + ext.toLowerCase(Locale.ROOT);
        Path root = Path.of(appProperties.getUploadDir()).toAbsolutePath().normalize();
        Path targetDir = root.resolve(safeSubdir).normalize();
        Path target = targetDir.resolve(filename).normalize();
        if (!target.startsWith(root) || targetDir.equals(root)) {
            throw new BadRequestException("非法的文件路径");
        }
        try {
            Files.createDirectories(targetDir);
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("图片保存失败：{}", target, e);
            throw new BadRequestException("图片保存失败，请重试");
        }
        return URL_PREFIX + safeSubdir + "/" + filename;
    }

    /**
     * 把压缩包导入目录中的临时图片转存到常规年月目录。
     * 非导入路径原样返回；源文件不存在时按原路径返回（兼容历史数据）。
     */
    public String adopt(String url) {
        if (url == null || !url.startsWith(URL_PREFIX + IMPORT_DIR + "/")) {
            return url;
        }
        Path root = Path.of(appProperties.getUploadDir()).toAbsolutePath().normalize();
        String relative = url.substring(URL_PREFIX.length());
        Path source = root.resolve(relative).normalize();
        if (!source.startsWith(root) || !Files.isRegularFile(source)) {
            return url;
        }
        byte[] bytes;
        String ext;
        try {
            bytes = Files.readAllBytes(source);
            ext = extensionOf(source.getFileName().toString());
        } catch (IOException e) {
            log.warn("读取导入临时图片失败，保留原路径：{}", url, e);
            return url;
        }
        String adopted;
        try {
            adopted = storeBytes(bytes, ext, monthDir());
        } catch (RuntimeException e) {
            log.warn("导入图片转存失败，保留原路径：{}", url, e);
            return url;
        }
        // 转存成功后删除临时文件；删除失败不影响已经转存成功的新路径
        try {
            Files.deleteIfExists(source);
        } catch (IOException e) {
            log.warn("删除导入临时图片失败：{}", source, e);
        }
        return adopted;
    }

    /** 删除一次导入会话的临时目录（仅允许 uploads/imports 下的 32 位十六进制目录）。 */
    public void cleanupImportSession(String sessionId) {
        if (sessionId == null || !sessionId.matches("^[a-f0-9]{32}$")) {
            throw new BadRequestException("导入会话标识不合法");
        }
        Path root = Path.of(appProperties.getUploadDir()).toAbsolutePath().normalize();
        Path dir = root.resolve(IMPORT_DIR).resolve(sessionId).normalize();
        if (!dir.startsWith(root.resolve(IMPORT_DIR))) {
            throw new BadRequestException("非法的文件路径");
        }
        deleteRecursively(dir);
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("删除临时文件失败：{}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理导入临时目录失败：{}", dir, e);
        }
    }

    private String monthDir() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    /** 提取小写扩展名；无扩展名返回空串（会被白名单拒绝）。 */
    private String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
