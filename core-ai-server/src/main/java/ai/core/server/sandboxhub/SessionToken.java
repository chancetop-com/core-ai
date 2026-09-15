package ai.core.server.sandboxhub;

/**
 * Immutable payload of a sandbox session token. Minted by {@link SessionTokenCodec#mint}
 * on core-ai-server and only ever verified by core-ai-server: the sandbox runtime stores
 * the token opaquely and replays it as {@code Authorization: Bearer} on every {@code /hub/*}
 * request, it never parses it. The token represents "session {@code sid}, sandbox {@code sbid}",
 * not a user — it can do exactly what the session's agent can already do.
 *
 * @param sid  chat session id
 * @param uid  owning user id (used for audit / caller context, never for authorization)
 * @param sbid sandbox id the token was minted for; a replaced sandbox invalidates the token
 * @param iat  issued-at, epoch seconds
 * @param exp  expiry, epoch seconds (iat + sandbox timeout)
 * @param n    32 hex chars (16 random bytes), replay/opacity guard
 * @author xander
 */
public record SessionToken(String sid, String uid, String sbid, long iat, long exp, String n) {
}
