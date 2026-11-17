package com.jqgg.glm53.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 压缩包导入 + 分类落库 + 前台展示的接口级联调测试（H2 内存库）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jqggimport;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.admin.password=test-pass",
        "app.upload-dir=target/test-uploads-import",
        "app.contract-dir=target/test-contracts-import",
        "app.rag.enabled=false",
})
class BillboardImageImportApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 压缩包导入后保存分类与封面并同步前台() throws Exception {
        String token = login();

        // ---- 1. 导入压缩包：3 张图片 + 1 个非图片 ----
        MvcResult imported = mockMvc.perform(multipart("/api/admin/billboards/images/import")
                        .file(new MockMultipartFile("file", "素材.zip", "application/zip", sampleZip()))
                        .header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accepted").value(3))
                .andExpect(jsonPath("$.data.skippedCount").value(1))
                .andReturn();
        JsonNode result = MAPPER.readTree(imported.getResponse().getContentAsString()).get("data");
        String sessionId = result.get("sessionId").asString();
        JsonNode images = result.get("images");
        assertThat(images.size()).isEqualTo(3);
        assertThat(images.get(0).get("category").asString()).isEqualTo("PROMO");
        assertThat(images.get(1).get("category").asString()).isEqualTo("REAL_PHOTO");
        assertThat(images.get(2).get("category").asString()).isEqualTo("DESIGN_DRAWING");
        String promoUrl = images.get(0).get("url").asString();
        String realUrl = images.get(1).get("url").asString();
        String designUrl = images.get(2).get("url").asString();

        // 导入后的临时图片可直接访问
        mockMvc.perform(get(promoUrl)).andExpect(status().isOk());

        // ---- 2. 保存广告牌：手动把第二张改为宣传图并设为封面 ----
        String meta = """
                [{"url":"%s","category":"PROMO","cover":false,"name":"宣传海报.jpg"},
                 {"url":"%s","category":"PROMO","cover":true,"name":"现场实拍.jpg"},
                 {"url":"%s","category":"DESIGN_DRAWING","cover":false,"name":"设计图纸.png"}]
                """.formatted(promoUrl, realUrl, designUrl);
        String keepImages = "[\"" + promoUrl + "\",\"" + realUrl + "\",\"" + designUrl + "\"]";

        MvcResult created = mockMvc.perform(multipart("/api/admin/billboards")
                        .param("name", "压缩包导入测试广告牌")
                        .param("code", "IT-ZIP-001")
                        .param("status", "AVAILABLE")
                        .param("keepImages", keepImages)
                        .param("imageMeta", meta)
                        .header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images.length()").value(3))
                .andExpect(jsonPath("$.data.imageMeta[0].cover").value(true))
                .andExpect(jsonPath("$.data.imageMeta[0].category").value("PROMO"))
                .andExpect(jsonPath("$.data.imageMeta[0].categoryLabel").value("宣传图"))
                .andReturn();
        JsonNode saved = MAPPER.readTree(created.getResponse().getContentAsString()).get("data");
        long id = saved.get("id").asLong();
        // 封面（手动调整过分类的实景图）排到首位，且临时导入地址已转存
        assertThat(saved.get("images").get(0).asString())
                .isEqualTo(saved.get("imageMeta").get(0).get("url").asString())
                .isNotEqualTo(realUrl);

        // ---- 3. 前台详情返回分类与封面，图片可访问 ----
        MvcResult detail = mockMvc.perform(get("/api/billboards/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageMeta.length()").value(3))
                .andExpect(jsonPath("$.data.imageMeta[0].cover").value(true))
                .andReturn();
        JsonNode detailData = MAPPER.readTree(detail.getResponse().getContentAsString()).get("data");
        String adoptedCover = detailData.get("images").get(0).asString();
        assertThat(adoptedCover).isNotEqualTo(realUrl);
        assertThat(adoptedCover).startsWith("/uploads/");
        mockMvc.perform(get(adoptedCover)).andExpect(status().isOk());

        // ---- 4. 清理导入会话（保存后已转存，清理不影响数据） ----
        mockMvc.perform(delete("/api/admin/billboards/images/import/" + sessionId)
                        .header("X-Admin-Token", token))
                .andExpect(status().isOk());
        mockMvc.perform(get(adoptedCover)).andExpect(status().isOk());
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test-pass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .get("data").get("token").asString();
    }

    /** 3 张不同尺寸/命名的图片 + 1 个文本文件。 */
    private byte[] sampleZip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            addEntry(zos, "素材/宣传图/宣传海报.jpg", image(1600, 600, "jpg", Color.decode("#D7263D")));
            addEntry(zos, "素材/实景/现场实拍照片.jpg", image(1200, 900, "jpg", Color.decode("#4C6B3C")));
            addEntry(zos, "素材/图纸/设计图纸.png", image(1000, 1414, "png", Color.WHITE));
            addEntry(zos, "readme.txt", "not an image".getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private void addEntry(ZipOutputStream zos, String name, byte[] bytes) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }

    private byte[] image(int width, int height, String format, Color color) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.setColor(format.equals("png") ? Color.BLACK : color.brighter());
        g.fillRect(0, 0, width / 3, height / 3);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
}
