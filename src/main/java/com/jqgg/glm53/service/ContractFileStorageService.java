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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 合同、模板、签章、签名等私有文件的存储服务。
 * 文件位于 app.contract-dir（默认 uploads-private/），不映射为 /uploads/** 静态资源，
 * 只能通过后台或签署令牌接口读取。
 */
@Service
public class ContractFileStorageService {

    private static final Logger log = LoggerFactory.getLogger(ContractFileStorageService.class);

    /** 图片类文件（签章 / 签名） */
    public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg");

    /** Word 模板 / 生成合同 */
    public static final Set<String> DOCX_EXTENSIONS = Set.of("docx");

    /** 线下已签合同允许的格式 */
    public static final Set<String> SIGNED_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg", "docx");

    /** 子目录只允许字母、数字、下划线、短横线和斜杠，防止路径穿越 */
    private static final Pattern SAFE_SUBDIR = Pattern.compile("^[A-Za-z0-9_\\-/]+$");

    private final Path baseDir;

    public ContractFileStorageService(AppProperties appProperties) {
        this.baseDir = Path.of(appProperties.getContractDir()).toAbsolutePath().normalize();
    }

    /** 保存签章 / 签名图片，返回私有目录相对路径。 */
    public String storeImage(MultipartFile file, String subdir) {
        return store(file, subdir, IMAGE_EXTENSIONS, 5L * 1024 * 1024);
    }

    /** 保存 Word 模板，返回私有目录相对路径。 */
    public String storeDocx(MultipartFile file, String subdir) {
        return store(file, subdir, DOCX_EXTENSIONS, 20L * 1024 * 1024);
    }

    /** 保存线下回签合同，返回私有目录相对路径。 */
    public String storeSignedContract(MultipartFile file, String subdir) {
        return store(file, subdir, SIGNED_EXTENSIONS, 20L * 1024 * 1024);
    }

    /** 保存字节内容（系统生成的签章 / 合同），返回私有目录相对路径。 */
    public String storeBytes(byte[] data, String subdir, String extension) {
        if (data == null || data.length == 0) {
            throw new BadRequestException("生成的文件内容为空");
        }
        String ext = normalizeExtension(extension);
        Path dir = prepareDir(subdir);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(filename).normalize();
        try {
            Files.write(target, data);
        } catch (IOException e) {
            log.error("私有文件写入失败：{}", target, e);
            throw new BadRequestException("文件保存失败，请重试");
        }
        return relative(dir, target);
    }

    /** 以固定文件名保存字节内容（示例签章等需要稳定路径的运行时资源）。 */
    public String storeBytesAt(byte[] data, String subdir, String filename) {
        if (data == null || data.length == 0) {
            throw new BadRequestException("生成的文件内容为空");
        }
        String ext = extensionOf(filename);
        if (!IMAGE_EXTENSIONS.contains(ext) && !DOCX_EXTENSIONS.contains(ext) && !"pdf".equals(ext)) {
            throw new BadRequestException("不支持的文件扩展名：" + filename);
        }
        Path dir = prepareDir(subdir);
        Path target = dir.resolve(safeFilename(filename, ext)).normalize();
        try {
            Files.write(target, data);
        } catch (IOException e) {
            log.error("私有文件写入失败：{}", target, e);
            throw new BadRequestException("文件保存失败，请重试");
        }
        return relative(dir, target);
    }

    /** 把已有文件复制到新的子目录（用于模板 / 签章快照）。 */
    public String copy(String sourceRelativePath, String subdir, String filename) {
        Path source = resolve(sourceRelativePath);
        if (!Files.exists(source)) {
            throw new BadRequestException("源文件不存在，无法生成合同副本");
        }
        Path dir = prepareDir(subdir);
        String safeName = safeFilename(filename, extensionOf(source.getFileName().toString()));
        Path target = dir.resolve(safeName).normalize();
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("私有文件复制失败：{} -> {}", source, target, e);
            throw new BadRequestException("文件复制失败，请重试");
        }
        return relative(dir, target);
    }

    /** 解析私有目录相对路径，并确保结果仍在私有目录内。 */
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BadRequestException("文件路径为空");
        }
        Path target = baseDir.resolve(relativePath).normalize();
        if (!target.startsWith(baseDir)) {
            throw new BadRequestException("非法的文件路径");
        }
        return target;
    }

    /** 读取私有文件字节。 */
    public byte[] read(String relativePath) {
        Path target = resolve(relativePath);
        if (!Files.exists(target)) {
            throw new BadRequestException("文件不存在或已被清理");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            log.error("私有文件读取失败：{}", target, e);
            throw new BadRequestException("文件读取失败，请重试");
        }
    }

    /** 静默删除私有文件（回滚 / 校验失败清理用，不抛异常）。 */
    public void deleteQuietly(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (Exception e) {
            log.warn("清理私有文件失败：{}", relativePath, e);
        }
    }

    /** 文件是否存在。 */
    public boolean exists(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        try {
            return Files.exists(resolve(relativePath));
        } catch (BadRequestException e) {
            return false;
        }
    }

    /** 读取文件大小；不存在时返回 0。 */
    public long size(String relativePath) {
        if (!exists(relativePath)) {
            return 0;
        }
        try {
            return Files.size(resolve(relativePath));
        } catch (IOException e) {
            return 0;
        }
    }

    /** 提取安全的原始文件名（去掉客户端路径）。 */
    public String originalName(MultipartFile file, String fallback) {
        String name = file == null ? null : file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return fallback;
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String cleaned = slash >= 0 ? name.substring(slash + 1) : name;
        cleaned = cleaned.replaceAll("[\\r\\n\\t]", "").trim();
        return cleaned.isBlank() ? fallback : cleaned;
    }

    /** 文件扩展名（小写，不含点）。 */
    public String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 私有存储根目录。 */
    public Path baseDir() {
        return baseDir;
    }

    // ------------------- 内部实现 -------------------

    private String store(MultipartFile file, String subdir, Set<String> allowed, long maxBytes) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请选择要上传的文件");
        }
        if (file.getSize() > maxBytes) {
            throw new BadRequestException("文件过大，最大允许 " + (maxBytes / 1024 / 1024) + "MB");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!allowed.contains(ext)) {
            throw new BadRequestException("不支持的文件格式：" + file.getOriginalFilename()
                    + "（仅支持 " + String.join("/", allowed) + "）");
        }
        Path dir = prepareDir(subdir);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(filename).normalize();
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("私有文件上传失败：{}", target, e);
            throw new BadRequestException("文件保存失败，请重试");
        }
        return relative(dir, target);
    }

    private Path prepareDir(String subdir) {
        if (subdir == null || subdir.isBlank() || !SAFE_SUBDIR.matcher(subdir).matches()) {
            throw new BadRequestException("非法的文件目录");
        }
        Path dir = baseDir.resolve(subdir).normalize();
        if (!dir.startsWith(baseDir)) {
            throw new BadRequestException("非法的文件目录");
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("创建私有文件目录失败：{}", dir, e);
            throw new BadRequestException("文件目录创建失败，请重试");
        }
        return dir;
    }

    private String relative(Path dir, Path target) {
        return baseDir.relativize(target).toString().replace('\\', '/');
    }

    private String normalizeExtension(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT).replace(".", "");
        if (!IMAGE_EXTENSIONS.contains(ext) && !DOCX_EXTENSIONS.contains(ext) && !Set.of("pdf").contains(ext)) {
            throw new BadRequestException("不支持的文件扩展名：" + extension);
        }
        return ext;
    }

    private String safeFilename(String filename, String extension) {
        String ext = extension == null || extension.isBlank() ? "bin" : extension;
        if (filename == null || filename.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "") + "." + ext;
        }
        String cleaned = filename.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (cleaned.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "") + "." + ext;
        }
        return cleaned;
    }
}
