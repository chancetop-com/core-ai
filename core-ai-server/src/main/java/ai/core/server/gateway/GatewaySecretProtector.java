package ai.core.server.gateway;

import core.framework.web.exception.BadRequestException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class GatewaySecretProtector {
    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public GatewaySecretProtector(String secret) {
        this.key = new SecretKeySpec(sha256(secret), "AES");
    }

    public String protect(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.startsWith(PREFIX)) return value;
        try {
            var iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            var payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new RuntimeException("failed to encrypt gateway secret", e);
        }
    }

    public String unprotect(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.startsWith(PREFIX)) return value;
        try {
            var payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (payload.length <= IV_LENGTH) throw new BadRequestException("invalid encrypted gateway secret");
            var iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
            var encrypted = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("failed to decrypt gateway secret");
        }
    }

    public boolean isProtected(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("failed to derive gateway secret key", e);
        }
    }
}
