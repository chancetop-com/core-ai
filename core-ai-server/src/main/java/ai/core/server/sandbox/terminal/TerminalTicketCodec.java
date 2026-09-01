package ai.core.server.sandbox.terminal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Mint/verify codec for {@link TerminalTicket}. Wire format (binding across
 * core-ai-server and the Go terminal gateway, byte-for-byte):
 * <pre>
 * base64url_nopad(payloadJson) + "." + base64url_nopad(hmacSha256(payloadJsonBytes, secret))
 * </pre>
 * where payloadJson is emitted with an explicit, fixed key order and no
 * whitespace:
 * <pre>
 * {"sid":"...","sbid":"...","cid":"...","ip":"...","port":N,"iat":N,"exp":N,"nonce":"..."}
 * </pre>
 * The Go counterpart lives at core-ai-terminal-gateway/ticket.go; the two
 * sides share a locked fixture string (see TerminalTicketCodecTest and
 * ticket_test.go) so a codec change that breaks either side fails its test.
 * <p>
 * {@link #mint} deliberately builds the payload with a {@link StringBuilder}
 * instead of a general-purpose JSON serializer so the key order above is
 * guaranteed rather than incidental. sid/sbid/cid/ip/nonce are internal
 * identifiers and addresses, never rendered as HTML/JS, so no escaping is
 * performed on them; as a defense against a malformed field breaking the
 * wire format, {@code mint} rejects any field containing {@code "} or
 * {@code \}.
 *
 * @author xander
 */
public final class TerminalTicketCodec {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public static String mint(TerminalTicket ticket, byte[] secret) {
        rejectUnsafe(ticket.sid(), "sid");
        rejectUnsafe(ticket.sbid(), "sbid");
        rejectUnsafe(ticket.cid(), "cid");
        rejectUnsafe(ticket.ip(), "ip");
        rejectUnsafe(ticket.nonce(), "nonce");

        byte[] payloadBytes = buildPayloadJson(ticket).getBytes(StandardCharsets.UTF_8);
        byte[] signature = hmac(payloadBytes, secret);
        return base64UrlEncode(payloadBytes) + "." + base64UrlEncode(signature);
    }

    public static TerminalTicket verify(String ticketString, byte[] secret, long nowEpochSec) {
        if (ticketString == null) throw new IllegalArgumentException("ticket must not be null");

        int firstDot = ticketString.indexOf('.');
        int lastDot = ticketString.lastIndexOf('.');
        if (firstDot < 0 || firstDot != lastDot) {
            throw new IllegalArgumentException("malformed ticket structure, expected exactly one '.'");
        }

        byte[] payloadBytes = base64UrlDecode(ticketString.substring(0, firstDot), "payload");
        byte[] signatureBytes = base64UrlDecode(ticketString.substring(firstDot + 1), "signature");

        byte[] expectedSignature = hmac(payloadBytes, secret);
        if (!MessageDigest.isEqual(expectedSignature, signatureBytes)) {
            throw new IllegalArgumentException("ticket signature mismatch");
        }

        TerminalTicket ticket = parsePayload(new String(payloadBytes, StandardCharsets.UTF_8));
        if (ticket.exp() <= nowEpochSec) {
            throw new IllegalArgumentException("ticket expired");
        }
        return ticket;
    }

    private static String buildPayloadJson(TerminalTicket t) {
        return new StringBuilder(160)
            .append("{\"sid\":\"").append(t.sid())
            .append("\",\"sbid\":\"").append(t.sbid())
            .append("\",\"cid\":\"").append(t.cid())
            .append("\",\"ip\":\"").append(t.ip())
            .append("\",\"port\":").append(t.port())
            .append(",\"iat\":").append(t.iat())
            .append(",\"exp\":").append(t.exp())
            .append(",\"nonce\":\"").append(t.nonce())
            .append("\"}")
            .toString();
    }

    private static void rejectUnsafe(String value, String fieldName) {
        if (value == null || value.indexOf('"') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("ticket field '" + fieldName + "' must not contain '\"' or '\\'");
        }
    }

    private static byte[] hmac(byte[] payload, byte[] secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to compute ticket signature", e);
        }
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] base64UrlDecode(String value, String partName) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed ticket " + partName + " encoding", e);
        }
    }

    // Parses the fixed 8-field payload shape emitted by buildPayloadJson. Field
    // order does not matter here (each field is located by its own key), only
    // the signature above is what actually protects the payload's integrity.
    private static TerminalTicket parsePayload(String json) {
        try {
            String sid = readString(json, "sid");
            String sbid = readString(json, "sbid");
            String cid = readString(json, "cid");
            String ip = readString(json, "ip");
            int port = Math.toIntExact(readLong(json, "port"));
            long iat = readLong(json, "iat");
            long exp = readLong(json, "exp");
            String nonce = readString(json, "nonce");
            return new TerminalTicket(sid, sbid, cid, ip, port, iat, exp, nonce);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed ticket payload", e);
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
        // Require a proper JSON terminator right after the digits, otherwise a
        // value like "port":8080.5 would silently truncate to 8080.
        if (end >= json.length() || json.charAt(end) != ',' && json.charAt(end) != '}') {
            throw new IllegalArgumentException("malformed numeric field: " + key);
        }
        return Long.parseLong(json.substring(start, end));
    }

    private TerminalTicketCodec() {
    }
}
