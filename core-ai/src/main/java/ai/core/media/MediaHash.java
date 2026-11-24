package ai.core.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * @author core-ai
 */
public final class MediaHash {
    public static String sha256(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes are required");
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            var hex = new StringBuilder(digest.length * 2);
            for (var value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16)).append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private MediaHash() {
    }
}
