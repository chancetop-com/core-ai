package ai.core.server.sandboxhub;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mints and verifies the sandbox session tokens that give scripts inside a sandbox the
 * session's own capabilities without holding any credential.
 * <p>
 * The token itself is stateless (HMAC, see {@link SessionTokenCodec}); this service adds
 * the two pieces of state the codec cannot hold: the signing secrets read from
 * {@code sys.sandbox.sessionToken.secret} (plus the pre-rotation secret accepted during a
 * rotation window) and the mint bookkeeping used to decide when a token has to be re-minted.
 * <p>
 * A token is minted whenever the session's sandbox materializes or is reattached, and
 * re-minted (then re-bound into the runtime) once it has burned through half of its
 * lifetime — see {@link #renewIfNeeded}. An empty secret disables the feature entirely:
 * {@link #enabled()} is false, minting is a no-op for callers and every verification fails.
 *
 * @author xander
 */
public class SessionTokenService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static String randomNonce() {
        var bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Primary HMAC secret; empty (default) disables the sandbox session identity. */
    public byte[] secret = new byte[0];
    /** Pre-rotation secret, accepted by {@link #verify} but never used for minting. */
    public byte[] previousSecret = new byte[0];

    private final Map<String, Minted> minted = new ConcurrentHashMap<>();

    public boolean enabled() {
        return secret.length > 0;
    }

    /**
     * Mints a token for (session, sandbox) and remembers it, so {@link #renewIfNeeded} can tell
     * whether the token in the sandbox is still fresh and was minted for the current sandbox.
     * Callers must skip this when {@link #enabled()} is false.
     */
    public MintedToken mint(String sessionId, String userId, String sandboxId, int ttlSeconds) {
        long issuedAt = epochSeconds();
        long expiresAt = issuedAt + ttlSeconds;
        minted.put(sessionId, new Minted(userId, sandboxId, issuedAt, expiresAt));
        var token = new SessionToken(sessionId, userId, sandboxId, issuedAt, expiresAt, randomNonce());
        return new MintedToken(SessionTokenCodec.mint(token, secret), expiresAt);
    }

    /**
     * Re-mints only when the current token is past half of its lifetime, or was minted for a
     * different sandbox (self-heal replacement); returns null while the existing token is still
     * fresh — or when nothing was ever minted for the session — so the caller can skip the rebind
     * round trip. Binds the same user as the minted token, since the payload identifies it.
     */
    public MintedToken renewIfNeeded(String sessionId, String sandboxId, int ttlSeconds) {
        var current = minted.get(sessionId);
        if (current == null) {
            return null;
        }
        if (current.sandboxId().equals(sandboxId) && current.halfLifeEnd() > epochSeconds()) {
            return null;
        }
        return mint(sessionId, current.userId(), sandboxId, ttlSeconds);
    }

    /**
     * Verifies signature and expiry; the caller is responsible for checking that the token's
     * sandbox is still the one bound to the session.
     *
     * @throws IllegalArgumentException on any invalid token, including "not configured"
     */
    public SessionToken verify(String token) {
        IllegalArgumentException firstFailure = null;
        for (var candidate : secrets()) {
            try {
                return SessionTokenCodec.verify(token, candidate, epochSeconds());
            } catch (IllegalArgumentException e) {
                if (firstFailure == null) firstFailure = e;
            }
        }
        throw firstFailure != null ? firstFailure : new IllegalArgumentException("session token secret is not configured");
    }

    /** Forgets the minted token of a released session. */
    public void unbind(String sessionId) {
        minted.remove(sessionId);
    }

    private List<byte[]> secrets() {
        var candidates = new ArrayList<byte[]>(2);
        if (secret.length > 0) candidates.add(secret);
        if (previousSecret.length > 0) candidates.add(previousSecret);
        return candidates;
    }

    private long epochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    /** A freshly minted token and the instant it stops being valid, as handed to the runtime's {@code /bind}. */
    public record MintedToken(String token, long expiresAt) {
    }

    private record Minted(String userId, String sandboxId, long iat, long exp) {
        long halfLifeEnd() {
            return iat + (exp - iat) / 2;
        }
    }
}
