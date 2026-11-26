package com.insbean.payroll.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private final CryptoService crypto = new CryptoService(
            "test-aes-key", "test-id-salt");

    @Test
    void 同一证件号不同写法产生相同盲索引() {
        assertThat(crypto.blindIndex("11010119900101123x"))
                .isEqualTo(crypto.blindIndex(" 11010119900101123X "));
        assertThat(crypto.blindIndex("11010119900101123X"))
                .isEqualTo(crypto.blindIndex("1101 0119 9001 0112 3X"));
    }

    @Test
    void 盲索引不可逆且不同输入产生不同结果() {
        String a = crypto.blindIndex("110101199001011234");
        String b = crypto.blindIndex("110101199001011235");

        assertThat(a).hasSize(64).isNotEqualTo(b);
        assertThat(a).doesNotContain("110101");
    }

    @Test
    void 加解密可往返且密文每次不同() {
        byte[] a = crypto.encrypt("110101199001011234");
        byte[] b = crypto.encrypt("110101199001011234");

        assertThat(a).isNotEqualTo(b);                       // GCM 随机 IV
        assertThat(crypto.decrypt(a)).isEqualTo("110101199001011234");
        assertThat(crypto.decrypt(b)).isEqualTo("110101199001011234");
    }

    @Test
    void 密文被篡改时解密失败而不是返回错误明文() {
        byte[] cipher = crypto.encrypt("110101199001011234");
        cipher[cipher.length - 1] ^= 0x01;

        assertThatThrownBy(() -> crypto.decrypt(cipher)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void 密钥缺失时拒绝构造() {
        assertThatThrownBy(() -> new CryptoService("", "salt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aes-key");
        assertThatThrownBy(() -> new CryptoService("key", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id-no-salt");
    }

    @Test
    void 掩码只保留末四位() {
        assertThat(CryptoService.maskIdNo("110101199001011234")).isEqualTo("****1234");
        assertThat(CryptoService.maskMobile("13800138000")).isEqualTo("138****8000");
        assertThat(CryptoService.maskIdNo(null)).isNull();
        assertThat(CryptoService.maskIdNo("  ")).isNull();
    }
}
