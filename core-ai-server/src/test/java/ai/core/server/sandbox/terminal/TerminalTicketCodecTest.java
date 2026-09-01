package ai.core.server.sandbox.terminal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks the cross-language ticket wire format: {@code base64url_nopad(payloadJson) +
 * "." + base64url_nopad(hmacSha256(payloadJson, secret))}. The fixture string in
 * {@link #mintProducesTheLockedFixtureString()} is copied verbatim into
 * core-ai-terminal-gateway/ticket_test.go; a codec change that breaks this test
 * breaks the Go side's contract test too.
 *
 * @author xander
 */
class TerminalTicketCodecTest {
    private static final byte[] FIXTURE_SECRET = "test-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    // Signs an arbitrary raw payload string exactly as TerminalTicketCodec.mint
    // would, without going through TerminalTicket/mint's field validation —
    // used to build signed-but-malformed payloads that mint() itself could
    // never produce (e.g. a non-integer numeric field).
    private static String signRawPayload(String payload, byte[] secret) throws GeneralSecurityException {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] signature = mac.doFinal(payloadBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes) + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    @Test
    void mintProducesTheLockedFixtureString() {
        var ticket = new TerminalTicket("s-fixture", "sb-fixture", "c-fixture", "10.0.0.9", 8080,
            1756700000L, 1756700030L, "0123456789abcdef0123456789abcdef");

        String actual = TerminalTicketCodec.mint(ticket, FIXTURE_SECRET);

        assertEquals("eyJzaWQiOiJzLWZpeHR1cmUiLCJzYmlkIjoic2ItZml4dHVyZSIsImNpZCI6ImMtZml4dHVyZSIsImlwIjoiMTAuMC4wLjkiLCJwb3J0Ijo4MDgwLCJpYXQiOjE3NTY3MDAwMDAsImV4cCI6MTc1NjcwMDAzMCwibm9uY2UiOiIwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZiJ9.Bt5LGyUd_VDUJkSaky8ajkX8humRHZhDwpUyRhjZIIM",
            actual);
    }

    @Test
    void verifyRoundtripsWhatMintProduces() {
        var ticket = new TerminalTicket("s-1", "sb-1", "cid-1", "10.1.2.3", 8090,
            1756700100L, 1756700130L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        byte[] secret = "another-secret".getBytes(StandardCharsets.UTF_8);

        String wire = TerminalTicketCodec.mint(ticket, secret);
        TerminalTicket verified = TerminalTicketCodec.verify(wire, secret, 1756700110L);

        assertEquals(ticket, verified);
    }

    @Test
    void verifyRejectsTamperedPayload() {
        var ticket = new TerminalTicket("s-1", "sb-1", "cid-1", "10.1.2.3", 8090,
            1756700100L, 1756700130L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        byte[] secret = "another-secret".getBytes(StandardCharsets.UTF_8);
        String wire = TerminalTicketCodec.mint(ticket, secret);

        int dot = wire.indexOf('.');
        String payloadPart = wire.substring(0, dot);
        String signaturePart = wire.substring(dot + 1);
        String tamperedPayload = new String(Base64.getUrlDecoder().decode(payloadPart), StandardCharsets.UTF_8)
            .replace("\"sid\":\"s-1\"", "\"sid\":\"s-2\"");
        String tamperedPayloadEncoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(tamperedPayload.getBytes(StandardCharsets.UTF_8));
        String tamperedWire = tamperedPayloadEncoded + "." + signaturePart;

        assertThrows(IllegalArgumentException.class, () -> TerminalTicketCodec.verify(tamperedWire, secret, 1756700110L));
    }

    @Test
    void verifyRejectsExpiredTicket() {
        var ticket = new TerminalTicket("s-1", "sb-1", "cid-1", "10.1.2.3", 8090,
            1756700100L, 1756700130L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        byte[] secret = "another-secret".getBytes(StandardCharsets.UTF_8);
        String wire = TerminalTicketCodec.mint(ticket, secret);

        assertThrows(IllegalArgumentException.class, () -> TerminalTicketCodec.verify(wire, secret, ticket.exp()));
    }

    @Test
    void verifyRejectsGarbage() {
        byte[] secret = "another-secret".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> TerminalTicketCodec.verify("not-a-ticket-at-all", secret, 0L));
        assertThrows(IllegalArgumentException.class, () -> TerminalTicketCodec.verify("!!!.!!!", secret, 0L));
        assertThrows(IllegalArgumentException.class, () -> TerminalTicketCodec.verify("a.b.c", secret, 0L));
    }

    @Test
    void mintRejectsFieldContainingQuote() {
        var ticket = new TerminalTicket("s-\"1", "sb-1", "cid-1", "10.1.2.3", 8090,
            1756700100L, 1756700130L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        byte[] secret = "another-secret".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> TerminalTicketCodec.mint(ticket, secret));
    }

    @Test
    void verifyRejectsNonIntegerNumericField() throws GeneralSecurityException {
        // A validly-signed payload whose "port" is not a bare integer
        // (mint() itself can never produce this; it is hand-built and signed
        // to exercise verify()'s payload parsing strictness).
        String payload = "{\"sid\":\"s-1\",\"sbid\":\"sb-1\",\"cid\":\"cid-1\",\"ip\":\"10.1.2.3\","
            + "\"port\":8080.5,\"iat\":1756700100,\"exp\":1756700130,"
            + "\"nonce\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}";
        byte[] secret = "another-secret".getBytes(StandardCharsets.UTF_8);
        String wire = signRawPayload(payload, secret);

        assertThrows(IllegalArgumentException.class, () -> TerminalTicketCodec.verify(wire, secret, 0L));
    }
}
