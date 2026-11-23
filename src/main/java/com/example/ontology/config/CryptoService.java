package com.example.ontology.config;

import com.example.ontology.common.OntologyException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 数据源密码的对称加密（AES-256-GCM）。
 *
 * <p>密码必须可还原（要连库），所以不能用不可逆哈希。主密钥来自配置/环境变量，
 * 数据库里只存密文。这是"不把凭据明文落库"的最低要求。</p>
 */
@Component
public class CryptoService {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(OntologyProperties properties) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(properties.cryptoKey());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("ontology.crypto-key 必须是 Base64", ex);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("ontology.crypto-key 必须是 32 字节（AES-256），当前 " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("加密失败", ex);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new OntologyException(com.example.ontology.common.ErrorCode.INTERNAL,
                    "数据源密码解密失败，请检查 ontology.crypto-key 是否与写入时一致");
        }
    }
}
