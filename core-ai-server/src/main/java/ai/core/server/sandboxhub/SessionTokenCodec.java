package ai.core.server.sandboxhub;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Mint/verify codec for {@link SessionToken}. Wire format:
 * <pre>
 * cst_ + base64url_nopad(payloadJson) + "." + base64url_nopad(hmacSha256(payloadJsonBytes, secret))
 * </pre>
 * where payloadJson is emitted with an explicit, fixed key order and no whitespace:
 * <pre>
 * {"sid":"...","uid":"...","sbid":"...","iat":N,"exp":N,"n":"..."}
 * </pre>
 * Built with a {@link StringBuilder} rather than the shared JSON mapper on purpose: the
 * mapper only sees bean getters/fields (a record serializes to {@code {}} under core-ng's
 * visibility config), and a signed payload must keep its exact bytes. sid/uid/sbid/n are
 * internal identifiers, so no escaping is performed on them; {@code mint} rejects any field
 * containing {@code "} or {@code \} to keep a malformed field from breaking the format.
 * <p>
 * There is no second implementation of this format — the sandbox runtime stores the token
 * opaquely and the Go/Python SDKs never parse it — so the format is only locked by
 * {@code SessionTokenCodecTest}.
 * <p>
 * {@link #verify} throws {@link IllegalArgumentException} with the failure reason (structure,
 * encoding, signature, expiry) — callers map every reason to a 401, the message is for logs.
 *
 * @author xander
 */
public final class SessionTokenCodec {
    public static final String PREFIX = "cst_";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public static String mint(SessionToken token, byte[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("session token secret is not configured");
        }
        rejectUnsafe(token.sid(), "sid");
        rejectUnsafe(token.uid(), "uid");
        rejectUnsafe(token.sbid(), "sbid");
        rejectUnsafe(token.n(), "n");

        byte[] payloadBytes = buildPayloadJson(token).getBytes(StandardCharsets.UTF_8);
        byte[] signature = hmac(payloadBytes, secret);
        return PREFIX + base64UrlEncode(payloadBytes) + "." + base64UrlEncode(signature);
    }

    public static SessionToken verify(String token, byte[] secret, long nowEpochSec) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("session token secret is not configured");
        }
        if (token == null || !token.startsWith(PREFIX)) {
            throw new IllegalArgumentException("malformed session token, missing '" + PREFIX + "' prefix");
        }

        var body = token.substring(PREFIX.length());
        int firstDot = body.indexOf('.');
        if (firstDot < 0 || firstDot != body.lastIndexOf('.')) {
            throw new IllegalArgumentException("malformed session token structure, expected exactly one '.'");
        }

        byte[] payloadBytes = base64UrlDecode(body.substring(0, firstDot), "payload");
        byte[] signatureBytes = base64UrlDecode(body.substring(firstDot + 1), "signature");

        if (!MessageDigest.isEqual(hmac(payloadBytes, secret), signatureBytes)) {
            throw new IllegalArgumentException("session token signature mismatch");
        }

        SessionToken sessionToken = parsePayload(new String(payloadBytes, StandardCharsets.UTF_8));
        if (sessionToken.exp() <= nowEpochSec) {
            throw new IllegalArgumentException("session token expired");
        }
        return sessionToken;
    }

    private static String buildPayloadJson(SessionToken t) {
        return new StringBuilder(128)
            .append("{\"sid\":\"").append(t.sid())
            .append("\",\"uid\":\"").append(t.uid())
            .append("\",\"sbid\":\"").append(t.sbid())
            .append("\",\"iat\":").append(t.iat())
            .append(",\"exp\":").append(t.exp())
            .append(",\"n\":\"").append(t.n())
            .append("\"}")
            .toString();
    }

    private static void rejectUnsafe(String value, String fieldName) {
        if (value == null || value.indexOf('"') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("session token field '" + fieldName + "' must not contain '\"' or '\\'");
        }
    }

    private static byte[] hmac(byte[] payload, byte[] secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to compute session token signature", e);
        }
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] base64UrlDecode(String value, String partName) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed session token " + partName + " encoding", e);
        }
    }

    // Parses the fixed 6-field payload shape emitted by buildPayloadJson. Field order
    // does not matter here (each field is located by its own key), only the signature
    // above is what actually protects the payload's integrity.
    private static SessionToken parsePayload(String json) {
        try {
            String sid = readString(json, "sid");
            String uid = readString(json, "uid");
            String sbid = readString(json, "sbid");
            long iat = readLong(json, "iat");
            long exp = readLong(json, "exp");
            String nonce = readString(json, "n");
            return new SessionToken(sid, uid, sbid, iat, exp, nonce);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed session token payload", e);
        }
    }

    private static String readString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) throw new IllegalArgumentException("missing string field: " + key);
        start += needle.length();
        int end = json.indexOf('"', start);
        if (end < 0) throw new IllegalArgumentException("unterminated string field: " + key);
        return json.substring(start, end);
    }

    private static long readLong(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) throw new IllegalArgumentException("missing numeric field: " + key);
        start += needle.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) throw new IllegalArgumentException("missing numeric field: " + key);
        if (end >= json.length() || json.charAt(end) != ',' && json.charAt(end) != '}') {
            throw new IllegalArgumentException("malformed numeric field: " + key);
        }
        return Long.parseLong(json.substring(start, end));
    }

    private SessionTokenCodec() {
    }
}
