package ai.core.server.sandboxhub;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
class SessionTokenCodecTest {
    private static final byte[] SECRET = "session-token-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static SessionToken token() {
        return new SessionToken("s-1", "u-1", "sb-1", 1756700100L, 1756700130L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    @Test
    void mintProducesPrefixedBase64UrlParts() {
        String wire = SessionTokenCodec.mint(token(), SECRET);

        assertTrue(wire.startsWith("cst_"));
        String body = wire.substring(SessionTokenCodec.PREFIX.length());
        assertEquals(1, body.chars().filter(c -> c == '.').count());
        String payload = new String(Base64.getUrlDecoder().decode(body.substring(0, body.indexOf('.'))), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"sid\":\"s-1\""), payload);
    }

    @Test
    void verifyRoundtripsWhatMintProduces() {
        String wire = SessionTokenCodec.mint(token(), SECRET);

        assertEquals(token(), SessionTokenCodec.verify(wire, SECRET, 1756700110L));
    }

    @Test
    void verifyRejectsTamperedPayload() {
        String wire = SessionTokenCodec.mint(token(), SECRET);
        String body = wire.substring(SessionTokenCodec.PREFIX.length());
        int dot = body.indexOf('.');
        String signaturePart = body.substring(dot + 1);
        String tamperedPayload = new String(Base64.getUrlDecoder().decode(body.substring(0, dot)), StandardCharsets.UTF_8)
                .replace("\"sid\":\"s-1\"", "\"sid\":\"s-2\"");
        String tamperedWire = SessionTokenCodec.PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(tamperedPayload.getBytes(StandardCharsets.UTF_8))
                + "." + signaturePart;

        assertThrows(IllegalArgumentException.class, () -> SessionTokenCodec.verify(tamperedWire, SECRET, 1756700110L));
    }

    @Test
    void verifyRejectsOtherSecret() {
        String wire = SessionTokenCodec.mint(token(), SECRET);

        assertThrows(IllegalArgumentException.class,
                () -> SessionTokenCodec.verify(wire, "another-secret".getBytes(StandardCharsets.UTF_8), 1756700110L));
    }

    @Test
    void verifyRejectsExpiredToken() {
        String wire = SessionTokenCodec.mint(token(), SECRET);

        assertThrows(IllegalArgumentException.class, () -> SessionTokenCodec.verify(wire, SECRET, 1756700130L));
    }

    @Test
    void verifyRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> SessionTokenCodec.verify("not-a-token", SECRET, 0L));
        assertThrows(IllegalArgumentException.class, () -> SessionTokenCodec.verify("ctk_abc.def", SECRET, 0L));
        assertThrows(IllegalArgumentException.class, () -> SessionTokenCodec.verify("cst_!!!.!!!", SECRET, 0L));
        assertThrows(IllegalArgumentException.class, () -> SessionTokenCodec.verify("cst_a.b.c", SECRET, 0L));
        assertThrows(IllegalArgumentException.class, () -> SessionTokenCodec.verify(null, SECRET, 0L));
    }

    @Test
    void mintRejectsEmptySecret() {
        assertThrows(IllegalArgumentException.class, () -> SessionTokenCodec.mint(token(), new byte[0]));
    }

    @Test
    void verifyRejectsSignedButMalformedPayload() throws GeneralSecurityException {
        // A validly-signed payload whose "exp" is not a bare integer (mint() itself can
        // never produce this; it is hand-built and signed to exercise verify()'s parsing).
        String payload = "{\"sid\":\"s-1\",\"uid\":\"u-1\",\"sbid\":\"sb-1\",\"iat\":1756700100,"
                + "\"exp\":1756700130.5,\"n\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        String wire = "cst_" + Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payloadBytes));

        assertThrows(IllegalArgumentException.class, () -> SessionTokenCodec.verify(wire, SECRET, 0L));
    }
}
