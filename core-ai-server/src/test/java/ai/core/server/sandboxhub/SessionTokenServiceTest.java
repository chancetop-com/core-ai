package ai.core.server.sandboxhub;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
class SessionTokenServiceTest {
    private static final byte[] SECRET = "session-token-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static SessionTokenService service() {
        var service = new SessionTokenService();
        service.secret = SECRET;
        return service;
    }

    @Test
    void mintedTokenVerifiesBackToTheSessionSandboxAndUser() {
        var service = service();

        var minted = service.mint("s-1", "u-1", "sb-1", 3600);

        var verified = service.verify(minted.token());
        assertEquals("s-1", verified.sid());
        assertEquals("u-1", verified.uid());
        assertEquals("sb-1", verified.sbid());
        assertTrue(minted.expiresAt() > System.currentTimeMillis() / 1000L);
    }

    @Test
    void disabledServiceCannotMintOrVerify() {
        var service = new SessionTokenService();

        assertFalse(service.enabled());
        assertThrows(IllegalArgumentException.class, () -> service.mint("s-1", "u-1", "sb-1", 3600));
        assertThrows(IllegalArgumentException.class, () -> service.verify("cst_a.b"));
    }

    @Test
    void verifyAcceptsTokenSignedWithPreviousSecretDuringRotation() {
        var previous = new SessionTokenService();
        previous.secret = "old-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        var mintedWithOldSecret = previous.mint("s-1", "u-1", "sb-1", 3600);

        var rotated = service();
        rotated.previousSecret = previous.secret;

        assertTrue(rotated.enabled());
        assertEquals("s-1", rotated.verify(mintedWithOldSecret.token()).sid());
    }

    @Test
    void verifyRejectsTokenFromAnUnrelatedSecret() {
        var other = new SessionTokenService();
        other.secret = "unrelated-secret-0123456789abc".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> service().verify(other.mint("s-1", "u-1", "sb-1", 3600).token()));
    }

    @Test
    void renewIfNeededKeepsFreshToken() {
        var service = service();
        var minted = service.mint("s-1", "u-1", "sb-1", 3600);

        assertNull(service.renewIfNeeded("s-1", "sb-1", 3600));
        assertEquals("s-1", service.verify(minted.token()).sid());
    }

    @Test
    void renewIfNeededRemintsPastHalfLife() {
        var service = service();
        var minted = service.mint("s-1", "u-1", "sb-1", 0);

        var renewed = service.renewIfNeeded("s-1", "sb-1", 3600);
        assertNotNull(renewed);
        assertNotEquals(minted.token(), renewed.token());
        assertEquals("sb-1", service.verify(renewed.token()).sbid());
    }

    @Test
    void renewIfNeededRemintsWhenTheSandboxWasReplaced() {
        var service = service();
        var minted = service.mint("s-1", "u-1", "sb-1", 3600);

        var renewed = service.renewIfNeeded("s-1", "sb-2", 3600);
        assertNotNull(renewed);
        assertNotEquals(minted.token(), renewed.token());
        assertEquals("sb-2", service.verify(renewed.token()).sbid());
    }

    @Test
    void renewIfNeededKeepsTheMintedUserOnRebind() {
        var service = service();
        service.mint("s-1", "u-1", "sb-1", 0);

        var renewed = service.renewIfNeeded("s-1", "sb-1", 3600);

        assertEquals("u-1", service.verify(renewed.token()).uid());
    }

    @Test
    void renewIfNeededSkipsSessionsThatWereNeverBound() {
        var service = service();

        assertNull(service.renewIfNeeded("s-1", "sb-1", 3600));
    }

    @Test
    void unbindForgetsTheMintedToken() {
        var service = service();
        service.mint("s-1", "u-1", "sb-1", 3600);

        service.unbind("s-1");

        assertNull(service.renewIfNeeded("s-1", "sb-1", 3600));
    }

    @Test
    void verifyRejectsExpiredMintedToken() {
        var service = service();
        var minted = service.mint("s-1", "u-1", "sb-1", -1);

        assertThrows(IllegalArgumentException.class, () -> service.verify(minted.token()));
    }
}
