package com.jqgg.glm53.web;

import com.jqgg.glm53.repository.ApiConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 管理模块集成测试：
 * 鉴权、增删改、Key 脱敏 / 保留 / 清空、默认配置切换、地图 Key 实时生效与回退、
 * 参数校验以及连通性测试失败结果。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jqggapi;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.admin.password=test-pass",
        "app.api.seed-defaults=false",
        "app.map.ak=fallback-map-ak",
        "app.rag.enabled=false",
        "app.upload-dir=target/test-uploads",
})
class ApiConfigApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ApiConfigRepository apiConfigRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        apiConfigRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test-pass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return dataOf(result).get("token").asString();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return MAPPER.readTree(body).get("data");
    }

    private JsonNode create(String token, String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/apis")
                        .header("X-Admin-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return dataOf(result);
    }

    @Test
    void 未登录时API管理返回401() throws Exception {
        mockMvc.perform(get("/api/admin/apis"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void 地图Key的脱敏默认切换与回退() throws Exception {
        String token = login();

        // 初始回退 application.yml
        mockMvc.perform(get("/api/site-config"))
                .andExpect(jsonPath("$.data.mapAk").value("fallback-map-ak"));

        // 第一条启用的地图配置自动成为默认
        JsonNode first = create(token, """
                {
                  "name": "腾讯地图主 Key",
                  "type": "MAP",
                  "provider": "TENCENT",
                  "baseUrl": "https://apis.map.qq.com",
                  "apiKey": "secret-abcdef123456",
                  "timeoutSeconds": 5,
                  "enabled": true,
                  "isDefault": false,
                  "remark": "测试"
                }
                """);
        long firstId = first.get("id").asLong();
        org.junit.jupiter.api.Assertions.assertTrue(first.get("isDefault").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(first.get("effective").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("sec****3456", first.get("apiKeyMasked").asString());
        org.junit.jupiter.api.Assertions.assertTrue(first.get("hasApiKey").asBoolean());

        // 列表接口绝不返回 Key 原文
        mockMvc.perform(get("/api/admin/apis").header("X-Admin-Token", token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].apiKeyMasked").value("sec****3456"));

        // 地图 Key 立即生效
        mockMvc.perform(get("/api/site-config"))
                .andExpect(jsonPath("$.data.mapAk").value("secret-abcdef123456"));

        // 新增第二条地图 Key 并设为默认
        JsonNode second = create(token, """
                {
                  "name": "腾讯地图备用 Key",
                  "type": "MAP",
                  "provider": "TENCENT",
                  "baseUrl": "https://apis.map.qq.com",
                  "apiKey": "second-key-987654",
                  "timeoutSeconds": 5,
                  "enabled": true,
                  "isDefault": false,
                  "remark": ""
                }
                """);
        long secondId = second.get("id").asLong();

        mockMvc.perform(post("/api/admin/apis/" + secondId + "/default")
                        .header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDefault").value(true))
                .andExpect(jsonPath("$.data.effective").value(true));
        mockMvc.perform(get("/api/site-config"))
                .andExpect(jsonPath("$.data.mapAk").value("second-key-987654"));

        // 编辑时 Key 留空保持原值
        mockMvc.perform(put("/api/admin/apis/" + secondId)
                        .header("X-Admin-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "腾讯地图备用 Key（改名）",
                                  "type": "MAP",
                                  "provider": "TENCENT",
                                  "baseUrl": "https://apis.map.qq.com",
                                  "apiKey": "",
                                  "clearApiKey": false,
                                  "timeoutSeconds": 5,
                                  "enabled": true,
                                  "isDefault": true,
                                  "remark": ""
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("腾讯地图备用 Key（改名）"))
                .andExpect(jsonPath("$.data.hasApiKey").value(true));
        mockMvc.perform(get("/api/site-config"))
                .andExpect(jsonPath("$.data.mapAk").value("second-key-987654"));

        // 显式清空 Key 后，默认线路无 Key，前台拿到空值
        mockMvc.perform(put("/api/admin/apis/" + secondId)
                        .header("X-Admin-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "腾讯地图备用 Key（改名）",
                                  "type": "MAP",
                                  "provider": "TENCENT",
                                  "baseUrl": "https://apis.map.qq.com",
                                  "clearApiKey": true,
                                  "timeoutSeconds": 5,
                                  "enabled": true,
                                  "isDefault": true,
                                  "remark": ""
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(false));
        mockMvc.perform(get("/api/site-config"))
                .andExpect(jsonPath("$.data.mapAk").value(""));

        // 删除默认线路后，第一条自动接替；再删除后回退 application.yml
        mockMvc.perform(delete("/api/admin/apis/" + secondId).header("X-Admin-Token", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/site-config"))
                .andExpect(jsonPath("$.data.mapAk").value("secret-abcdef123456"));

        mockMvc.perform(delete("/api/admin/apis/" + firstId).header("X-Admin-Token", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/site-config"))
                .andExpect(jsonPath("$.data.mapAk").value("fallback-map-ak"));
    }

    @Test
    void 表单校验与名称唯一() throws Exception {
        String token = login();

        // 类型与协议不匹配
        mockMvc.perform(post("/api/admin/apis")
                        .header("X-Admin-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"非法协议","type":"LLM","provider":"OLLAMA","baseUrl":"https://x.test/v1","model":"m"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("接口类型与协议不匹配：OLLAMA"));

        // 非法地址
        mockMvc.perform(post("/api/admin/apis")
                        .header("X-Admin-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"非法地址","type":"LLM","provider":"OPENAI","baseUrl":"ftp://x.test","model":"m"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("接口地址格式不正确，请使用 http:// 或 https:// 开头的完整地址"));

        // 向量模型缺少模型名
        mockMvc.perform(post("/api/admin/apis")
                        .header("X-Admin-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"缺模型","type":"EMBEDDING","provider":"OLLAMA","baseUrl":"http://127.0.0.1:11434"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请填写模型名称"));

        create(token, """
                {"name":"唯一名称","type":"LLM","provider":"OPENAI","baseUrl":"https://api.deepseek.com/v1","apiKey":"sk-test-123456","model":"deepseek-chat"}
                """);

        // 名称重复
        mockMvc.perform(post("/api/admin/apis")
                        .header("X-Admin-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"唯一名称","type":"OTHER","provider":"HTTP","baseUrl":"https://example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("配置名称已存在：唯一名称"));
    }

    @Test
    void 连通性测试失败时返回业务结果而非500() throws Exception {
        String token = login();
        JsonNode saved = create(token, """
                {"name":"本地不可达接口","type":"OTHER","provider":"HTTP","baseUrl":"http://127.0.0.1:1/ping","timeoutSeconds":2}
                """);
        long id = saved.get("id").asLong();

        mockMvc.perform(post("/api/admin/apis/" + id + "/test")
                        .header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").exists());

        mockMvc.perform(post("/api/admin/apis/test")
                        .header("X-Admin-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"OTHER","provider":"HTTP","baseUrl":"http://127.0.0.1:1/ping","timeoutSeconds":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false));
    }

    @Test
    void 未启用配置时保留在列表中但不生效() throws Exception {
        String token = login();
        JsonNode saved = create(token, """
                {"name":"停用的向量模型","type":"EMBEDDING","provider":"OLLAMA","baseUrl":"http://127.0.0.1:11434","model":"bge-m3","enabled":false}
                """);
        org.junit.jupiter.api.Assertions.assertFalse(saved.get("enabled").asBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(saved.get("isDefault").asBoolean());

        mockMvc.perform(get("/api/admin/apis").header("X-Admin-Token", token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].effective").value(false))
                .andExpect(jsonPath("$.data[0].enabled").value(false));
    }
}
