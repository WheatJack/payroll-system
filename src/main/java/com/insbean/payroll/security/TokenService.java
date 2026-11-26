package com.insbean.payroll.security;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.security.mapper.AuthMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 无状态 HMAC-SHA256 签名 token。
 *
 * <p>token 结构：{@code base64url("userId:expireAtMillis:tokenVersion") + "." + base64url(hmac)}。
 * 服务端不保存会话，因而可以水平扩容；代价是签发后无法单独吊销，
 * 所以引入 tokenVersion：库里 +1，所有旧 token 立刻 401（改密码、强制下线都走这条路）。
 */
@Service
public class TokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlMinutes;
    private final AuthMapper authMapper;

    public TokenService(@Value("${payroll.security.token-secret}") String secret,
                        @Value("${payroll.security.token-ttl-minutes:720}") long ttlMinutes,
                        AuthMapper authMapper) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("payroll.security.token-secret 未配置，拒绝启动");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlMinutes = ttlMinutes;
        this.authMapper = authMapper;
    }

    public String issue(Long userId) {
        long expireAt = System.currentTimeMillis() + ttlMinutes * 60_000L;
        String payload = userId + ":" + expireAt + ":" + currentVersion(userId);
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
    }

    /**
     * @return 签发时的用户 ID
     * @throws BizException 401 当格式错误、签名不匹配、已过期或 tokenVersion 已失效
     */
    public Long parse(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw unauthorized();
        }
        String payload;
        try {
            payload = new String(DECODER.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unauthorized();
        }
        byte[] expected = sign(payload).getBytes(StandardCharsets.UTF_8);
        byte[] actual = token.substring(dot + 1).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw unauthorized();
        }
        String[] parts = payload.split(":");
        if (parts.length != 3) {
            throw unauthorized();
        }
        try {
            long userId = Long.parseLong(parts[0]);
            long expireAt = Long.parseLong(parts[1]);
            int tokenVersion = Integer.parseInt(parts[2]);
            if (expireAt < System.currentTimeMillis()) {
                throw unauthorized();
            }
            // 库里版本已被 +1（改密码/强制下线）→ 旧 token 作废
            if (currentVersion(userId) != tokenVersion) {
                throw unauthorized();
            }
            return userId;
        } catch (NumberFormatException e) {
            throw unauthorized();
        }
    }

    private int currentVersion(Long userId) {
        Integer version = authMapper.findTokenVersion(userId);
        return version == null ? 0 : version;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("token 签名失败", e);
        }
    }

    private static BizException unauthorized() {
        return new BizException(401, "未认证或登录已过期");
    }
}
