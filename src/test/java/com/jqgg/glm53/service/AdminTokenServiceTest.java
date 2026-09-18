package com.jqgg.glm53.service;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 后台登录令牌服务测试（纯单元测试，不依赖 Spring 容器与数据库）。
 */
class AdminTokenServiceTest {

    private AdminTokenService serviceWith(String password, int ttlHours) {
        AppProperties props = new AppProperties();
        props.getAdmin().setPassword(password);
        props.getAdmin().setTokenTtlHours(ttlHours);
        return new AdminTokenService(props);
    }

    @Test
    void 密码正确时签发令牌_密码错误时拒绝() {
        AdminTokenService service = serviceWith("admin123", 2);

        assertThatThrownBy(() -> service.login("wrong"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("密码错误");
        assertThatThrownBy(() -> service.login(null))
                .isInstanceOf(BadRequestException.class);

        String token = service.login("admin123");
        assertThat(token).isNotBlank();
    }

    @Test
    void 令牌校验与退出作废() {
        AdminTokenService service = serviceWith("admin123", 2);
        String token = service.login("admin123");

        assertThat(service.isValid(token)).isTrue();
        assertThat(service.isValid("not-a-token")).isFalse();
        assertThat(service.isValid(null)).isFalse();

        service.logout(token);
        assertThat(service.isValid(token)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 令牌过期后失效() throws Exception {
        AdminTokenService service = serviceWith("admin123", 1);
        String token = service.login("admin123");
        assertThat(service.isValid(token)).isTrue();

        // 通过反射把过期时间改为过去，模拟令牌过期（不引入时钟抽象）
        java.lang.reflect.Field field = AdminTokenService.class.getDeclaredField("tokens");
        field.setAccessible(true);
        java.util.Map<String, Long> tokens = (java.util.Map<String, Long>) field.get(service);
        tokens.put(token, System.currentTimeMillis() - 1);

        assertThat(service.isValid(token)).isFalse();
    }
}
