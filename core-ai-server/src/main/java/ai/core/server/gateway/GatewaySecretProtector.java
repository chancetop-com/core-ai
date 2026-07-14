package ai.core.server.gateway;

import core.framework.web.exception.BadRequestException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

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
    private final SecretKeySpec legacyKey;

    public GatewaySecretProtector(String secret) {
        this(secret, null);
    }

    /**
     * @param secret       key material used to encrypt and decrypt secrets
     * @param legacySecret optional previous key material, only tried for decrypting
     *                     values encrypted before a key rotation
     */
    public GatewaySecretProtector(String secret, String legacySecret) {
        if (secret == null || secret.isBlank()) throw new Error("gateway secret key must not be blank");
        this.key = new SecretKeySpec(sha256(secret), "AES");
        this.legacyKey = legacySecret == null || legacySecret.isBlank() ? null : new SecretKeySpec(sha256(legacySecret), "AES");
    }

    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
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
        var payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
        if (payload.length <= IV_LENGTH) throw new BadRequestException("invalid encrypted gateway secret");
        var decrypted = decrypt(payload, key);
        if (decrypted == null && legacyKey != null) decrypted = decrypt(payload, legacyKey);
        if (decrypted == null) throw new BadRequestException("failed to decrypt gateway secret");
        return decrypted;
    }

    public boolean isProtected(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    private String decrypt(byte[] payload, SecretKeySpec key) {
        try {
            var iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
            var encrypted = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("failed to derive gateway secret key", e);
        }
    }
}
