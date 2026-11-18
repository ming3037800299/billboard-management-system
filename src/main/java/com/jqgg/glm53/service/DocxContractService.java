package com.jqgg.glm53.service;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.data.PictureRenderData;
import com.deepoove.poi.data.PictureType;
import com.deepoove.poi.data.Pictures;
import com.jqgg.glm53.exception.BadRequestException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word 合同模板扫描与渲染。
 * 文本占位符：{{字段名}}；图片占位符：{{@甲方签章}}、{{@乙方签名}}。
 */
@Service
public class DocxContractService {

    private static final Logger log = LoggerFactory.getLogger(DocxContractService.class);

    /** 匹配 {{字段}} / {{@图片字段}}，字段长度限制避免误匹配整段文本。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(@?)([^{}\\r\\n]{1,80})}}");

    /** 1×1 透明 PNG，用于填充模板里未提供的可选图片占位符。 */
    private static final byte[] TRANSPARENT_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** 扫描 docx 中全部占位符（含正文、表格、页眉页脚），返回原始标签，如 {{合同编号}}、{{@甲方签章}}。 */
    public Set<String> scanPlaceholders(Path docxPath) {
        try (InputStream in = Files.newInputStream(docxPath)) {
            return scanPlaceholders(in, docxPath.toString());
        } catch (IOException e) {
            log.warn("无法读取合同模板：{}", docxPath, e);
            return new LinkedHashSet<>();
        }
    }

    /** 直接扫描上传文件内容，用于“先校验占位符、再落盘”，避免校验失败留下孤儿模板。 */
    public Set<String> scanPlaceholders(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请选择要上传的 .docx 合同模板");
        }
        try (InputStream in = file.getInputStream()) {
            return scanPlaceholders(in, file.getOriginalFilename());
        } catch (IOException e) {
            log.warn("无法读取上传的合同模板：{}", file.getOriginalFilename(), e);
            throw new BadRequestException("合同模板读取失败，请确认文件为有效的 .docx");
        }
    }

    private Set<String> scanPlaceholders(InputStream in, String source) {
        Set<String> tags = new LinkedHashSet<>();
        try (XWPFDocument doc = new XWPFDocument(in)) {
            scanBody(doc, tags);
            for (XWPFHeader header : doc.getHeaderList()) {
                scanHeaderFooter(header.getBodyElements(), tags);
            }
            for (XWPFFooter footer : doc.getFooterList()) {
                scanHeaderFooter(footer.getBodyElements(), tags);
            }
        } catch (Exception e) {
            log.warn("无法读取合同模板：{}", source, e);
        }
        return tags;
    }

    /**
     * 按模板渲染合同。
     *
     * @param templatePath 模板 docx
     * @param outputPath   输出 docx
     * @param fields       文本字段（键为占位符名称，不含花括号）
     * @param sealBytes    甲方签章 PNG；为空时使用占位图
     * @param signBytes    乙方签名 PNG；为空时使用“待乙方签署”占位图
     */
    public void render(Path templatePath, Path outputPath, Map<String, String> fields,
                       byte[] sealBytes, byte[] signBytes) {
        Set<String> tags = scanPlaceholders(templatePath);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (fields != null) {
            fields.forEach((k, v) -> data.put(k, v == null ? "" : v));
        }

        // 图片占位符：键名不含 @。甲方签章必须有；乙方签名在草稿阶段用占位图。
        data.put("甲方签章", image(sealBytes != null && sealBytes.length > 0
                ? sealBytes : ContractImageFactory.sealPlaceholder(), 105, 105));
        data.put("乙方签名", image(signBytes != null && signBytes.length > 0
                ? signBytes : ContractImageFactory.signaturePlaceholder(), 155, 52));

        // 模板里存在但数据未提供的字段：文本填空串、图片填透明图，避免残留占位符。
        for (String tag : tags) {
            boolean picture = tag.startsWith("{{@");
            String name = tag.substring(picture ? 3 : 2, tag.length() - 2).trim();
            if (name.isEmpty()) {
                continue;
            }
            if (picture) {
                data.putIfAbsent(name, image(TRANSPARENT_PNG, 1, 1));
            } else {
                data.putIfAbsent(name, "");
            }
        }

        try {
            Files.createDirectories(outputPath.getParent());
            try (InputStream in = Files.newInputStream(templatePath);
                 XWPFTemplate template = XWPFTemplate.compile(in)) {
                template.render(data);
                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    template.writeAndClose(out);
                }
            }
        } catch (IOException e) {
            log.error("合同文件生成失败：模板={}，输出={}", templatePath, outputPath, e);
            throw new BadRequestException("合同文件生成失败，请检查 Word 模板格式后重试");
        }
    }

    private PictureRenderData image(byte[] bytes, int width, int height) {
        PictureType type = PictureType.suggestFileType(bytes);
        if (type == null) {
            type = PictureType.PNG;
        }
        return Pictures.ofBytes(bytes, type)
                .size(width, height)
                .center()
                .create();
    }

    private void scanBody(XWPFDocument doc, Set<String> tags) {
        scanHeaderFooter(doc.getBodyElements(), tags);
    }

    private void scanHeaderFooter(java.util.List<IBodyElement> elements, Set<String> tags) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                collect(paragraph.getText(), tags);
            } else if (element instanceof XWPFTable table) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        scanHeaderFooter(cell.getBodyElements(), tags);
                    }
                }
            }
        }
    }

    private void collect(String text, Set<String> tags) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String tag = matcher.group(0).replace(" ", "");
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
    }
}
