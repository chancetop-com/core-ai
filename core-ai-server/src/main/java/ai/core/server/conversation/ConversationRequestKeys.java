package ai.core.server.conversation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * @author core-ai
 */
public final class ConversationRequestKeys {
    public static String requestKey(String subject, String kind, String scope, String clientRequestId) {
        return sha256(nullToEmpty(subject) + '\u0000' + nullToEmpty(kind) + '\u0000'
                + nullToEmpty(scope) + '\u0000' + nullToEmpty(clientRequestId));
    }

    public static String payloadHash(String subject, String kind, String scope, String payload) {
        return sha256(nullToEmpty(subject) + '\u0000' + nullToEmpty(kind) + '\u0000'
                + nullToEmpty(scope) + '\u0000' + nullToEmpty(payload));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private ConversationRequestKeys() {
    }
}
