package com.jqgg.glm53.service;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.exception.BadRequestException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台登录令牌服务（初版内存实现）。
 * 密码验证通过后签发随机令牌，令牌保存在内存中并带过期时间；
 * 有效期内的每次请求自动滑动续期。应用重启后令牌失效，需重新登录。
 */
@Service
public class AdminTokenService {

    private final AppProperties appProperties;

    /** token → 过期时间戳（毫秒） */
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    public AdminTokenService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 校验管理密码；通过则签发新令牌。
     *
     * @param password 待验证密码
     * @return 签发的令牌
     */
    public String login(String password) {
        if (!matches(password, appProperties.getAdmin().getPassword())) {
            throw new BadRequestException("管理密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, System.currentTimeMillis() + ttlMillis());
        return token;
    }

    /** 退出登录：作废指定令牌。 */
    public void logout(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    /**
     * 校验令牌是否有效；有效则滑动续期。空令牌直接判无效。
     */
    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long expiry = tokens.get(token);
        if (expiry == null) {
            return false;
        }
        if (expiry < System.currentTimeMillis()) {
            tokens.remove(token);
            return false;
        }
        tokens.put(token, System.currentTimeMillis() + ttlMillis());
        return true;
    }

    /** 令牌有效期（毫秒）。 */
    private long ttlMillis() {
        return Math.max(1, appProperties.getAdmin().getTokenTtlHours()) * 3600_000L;
    }

    /** 恒定时间比较，避免时序侧信道（初版即养成良好的比较习惯）。 */
    private boolean matches(String raw, String expected) {
        if (raw == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                raw.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
