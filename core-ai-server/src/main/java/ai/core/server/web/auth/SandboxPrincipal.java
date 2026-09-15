package ai.core.server.web.auth;

/**
 * Identity of a request that comes from inside a session's sandbox, replayed by the sandbox
 * runtime from the {@code cst_} session token it holds.
 * <p>
 * It names "this session's sandbox", not a user session: endpoints behind it must only ever
 * act on the session and sandbox it names, and must never treat it as a login of
 * {@link #userId}.
 *
 * @author xander
 */
public record SandboxPrincipal(String sessionId, String userId, String sandboxId) {
}
