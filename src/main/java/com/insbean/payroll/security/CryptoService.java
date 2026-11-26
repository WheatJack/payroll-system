package com.insbean.payroll.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 证件号 / 手机号的对称加密与盲索引。
 *
 * <p>两个密钥分离，职责不可混用：
 * <ul>
 *   <li>{@code payroll.security.aes-key} —— AES-256-GCM，负责"能还原"的机密性；</li>
 *   <li>{@code payroll.security.id-no-salt} —— HMAC-SHA256，负责"能查重但不可还原"的盲索引。</li>
 * </ul>
 * 都用 SHA-256 派生成 32 字节，因此运维注入的密钥长度不受限制，但绝不允许为空——
 * 缺失即启动失败，避免用空密钥静默跑起来。
 *
 * <p>密文格式：{@code IV(12B) || ciphertext || tag(16B)}。IV 每次随机，
 * 因此同一明文每次加密结果不同，攻击者无法通过比对密文判断是否同一人。
 */
@Service
public class CryptoService {

    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec aesKey;
    private final byte[] indexKey;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${payroll.security.aes-key}") String aesKey,
                         @Value("${payroll.security.id-no-salt}") String idNoSalt) {
        if (aesKey == null || aesKey.isBlank()) {
            throw new IllegalStateException("payroll.security.aes-key 未配置，拒绝启动");
        }
        if (idNoSalt == null || idNoSalt.isBlank()) {
            throw new IllegalStateException("payroll.security.id-no-salt 未配置，拒绝启动");
        }
        this.aesKey = new SecretKeySpec(sha256(aesKey), "AES");
        this.indexKey = sha256(idNoSalt);
    }

    public byte[] encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public String decrypt(byte[] cipher) {
        if (cipher == null) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(cipher);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
            Cipher aes = Cipher.getInstance(AES_TRANSFORMATION);
            aes.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(aes.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败：密文可能被篡改", e);
        }
    }

    /**
     * 盲索引：归一化（去空白、字母大写）后做 HMAC-SHA256，十六进制小写。
     * 归一化是必需的——"11010119900101123x" 与 " 11010119900101123X " 是同一个人，
     * 不归一化就会在库里产生两条人员记录。
     */
    public String blindIndex(String plain) {
        if (plain == null) {
            return null;
        }
        String normalized = plain.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(indexKey, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("盲索引计算失败", e);
        }
    }

    /** 证件号掩码：{@code ****1234}。 */
    public static String maskIdNo(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        String trimmed = plain.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    /** 手机号掩码：{@code 138****8000}。 */
    public static String maskMobile(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        String trimmed = plain.trim();
        if (trimmed.length() <= 7) {
            return "****";
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }
}
