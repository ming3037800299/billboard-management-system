package com.jqgg.glm53.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全链路 API 集成测试（H2 内存库，MySQL 兼容模式）。
 * 覆盖：密码登录 → 令牌鉴权 → 新增（含图片上传）→ 前台同步展示 →
 *       编辑 → 软删除（前台隐藏）→ 恢复 → 筛选查询 → 退出登录。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jqggflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.admin.password=test-pass",
        "app.upload-dir=target/test-uploads",
        "app.map.ak=test-ak",
        "app.rag.enabled=false",
})
class AdminApiFlowTest {

    /** 1×1 像素 PNG（Base64），用于图片上传测试。 */
    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** 登录并返回令牌。 */
    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode data = MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get("token").asString();
    }

    @Test
    void 密码错误时无法登录() throws Exception {
        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("管理密码错误"));
    }

    @Test
    void 未携带令牌时管理接口返回401() throws Exception {
        mockMvc.perform(get("/api/admin/billboards"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void 完整增删改查与前台同步流程() throws Exception {
        String token = login();

        // ---- 1. 新增（multipart：字段 + 图片文件） ----
        MvcResult created = mockMvc.perform(multipart("/api/admin/billboards")
                        .file(new MockMultipartFile("images", "photo.png", "image/png", PNG_BYTES))
                        .param("name", "集成测试广告牌")
                        .param("code", "IT-BB-001")
                        .param("road", "京沪高速G2")
                        .param("section", "测试段 K1+000")
                        .param("longitude", "115.464600")
                        .param("latitude", "38.748900")
                        .param("price", "26000")
                        .param("priceUnit", "元/月")
                        .param("status", "AVAILABLE")
                        .header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("集成测试广告牌"))
                .andExpect(jsonPath("$.data.images.length()").value(1))
                .andReturn();
        JsonNode saved = MAPPER.readTree(created.getResponse().getContentAsString()).get("data");
        long id = saved.get("id").asLong();
        String imageUrl = saved.get("images").get(0).asString();

        // ---- 2. 上传的图片可访问（静态资源映射 /uploads/**） ----
        mockMvc.perform(get(imageUrl)).andExpect(status().isOk());

        // ---- 3. 前台公开接口立即可见（同步展示） ----
        mockMvc.perform(get("/api/billboards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].statusLabel").value("可租赁"));

        mockMvc.perform(get("/api/billboards/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("IT-BB-001"));

        mockMvc.perform(get("/api/billboards/roads"))
                .andExpect(jsonPath("$.data[0]").value("京沪高速G2"));

        // ---- 4. 前台筛选：状态不匹配则查不到 ----
        mockMvc.perform(get("/api/billboards").param("status", "RENTED"))
                .andExpect(jsonPath("$.data.length()").value(0));

        // ---- 5. 编辑：改报价与状态，保留旧图 ----
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/admin/billboards/" + id)
                        .param("name", "集成测试广告牌（已租）")
                        .param("code", "IT-BB-001")
                        .param("road", "京沪高速G2")
                        .param("price", "30000")
                        .param("priceUnit", "元/月")
                        .param("status", "RENTED")
                        .param("keepImages", "[\"" + imageUrl + "\"]")
                        .header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(30000.0))
                .andExpect(jsonPath("$.data.statusLabel").value("已租赁"))
                .andExpect(jsonPath("$.data.images.length()").value(1));

        // 前台同步体现修改
        mockMvc.perform(get("/api/billboards/" + id))
                .andExpect(jsonPath("$.data.price").value(30000.0))
                .andExpect(jsonPath("$.data.statusLabel").value("已租赁"));

        // ---- 6. 编号唯一校验 ----
        mockMvc.perform(multipart("/api/admin/billboards")
                        .param("name", "重复编号")
                        .param("code", "IT-BB-001")
                        .param("status", "AVAILABLE")
                        .header("X-Admin-Token", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("广告牌编号已存在：IT-BB-001（请更换编号）"));

        // ---- 7. 表单校验：缺少必填字段 ----
        mockMvc.perform(multipart("/api/admin/billboards")
                        .param("code", "IT-BB-002")
                        .param("status", "AVAILABLE")
                        .header("X-Admin-Token", token))
                .andExpect(status().isBadRequest());

        // ---- 8. 坐标范围校验 ----
        mockMvc.perform(multipart("/api/admin/billboards")
                        .param("name", "坐标超界")
                        .param("code", "IT-BB-003")
                        .param("longitude", "999")
                        .param("status", "AVAILABLE")
                        .header("X-Admin-Token", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("经度超出合理范围（-180 ~ 180）"));

        // ---- 9. 软删除：前台立即隐藏，后台含已删除可见 ----
        mockMvc.perform(delete("/api/admin/billboards/" + id)
                        .header("X-Admin-Token", token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/billboards"))
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/billboards/" + id))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/billboards")
                        .param("includeDeleted", "true")
                        .header("X-Admin-Token", token))
                .andExpect(jsonPath("$.data[0].deleted").value(true));

        // ---- 10. 恢复：前台重新可见 ----
        mockMvc.perform(post("/api/admin/billboards/" + id + "/restore")
                        .header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(false));
        mockMvc.perform(get("/api/billboards"))
                .andExpect(jsonPath("$.data.length()").value(1));

        // ---- 11. 退出登录后令牌失效 ----
        mockMvc.perform(post("/api/admin/logout").header("X-Admin-Token", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/billboards").header("X-Admin-Token", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 站点配置接口() throws Exception {
        mockMvc.perform(get("/api/site-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteName").value("公路广告牌租赁定位系统"))
                .andExpect(jsonPath("$.data.mapAk").value("test-ak"));
    }
}
