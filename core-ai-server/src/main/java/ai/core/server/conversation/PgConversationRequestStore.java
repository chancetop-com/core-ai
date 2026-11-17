package ai.core.server.conversation;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.Optional;

/**
 * PostgreSQL-backed request receipts. The unique request key makes the claim atomic across
 * replicas; the claim token distinguishes the winner from a caller that arrived a moment later.
 *
 * @author core-ai
 */
public class PgConversationRequestStore implements ConversationRequestStore {
    private static final String TABLE = "app.conversation_requests";
    private static final String SELECT = "SELECT request_key, subject, kind, session_id, payload_hash, status, "
            + "response_json, error_message, claim_token, created_at, expires_at FROM " + TABLE;

    private static Receipt toReceipt(PgConversationRequestRow row) {
        return new Receipt(row.subject, row.kind, row.sessionId, row.payloadHash, row.status,
                row.responseJson, row.errorMessage, row.claimToken);
    }

    private static String abbreviate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    @Inject
    Database database;

    @Override
    public Optional<Receipt> find(String requestKey) {
        return database.selectOne(SELECT + " WHERE request_key = ? AND expires_at > now()",
                PgConversationRequestRow.class, requestKey).map(PgConversationRequestStore::toReceipt);
    }

    @Override
    public Claim claim(String requestKey, String subject, String kind, String sessionId, String payloadHash, String claimToken) {
        database.execute("INSERT INTO " + TABLE + " (request_key, subject, kind, session_id, payload_hash, status, claim_token, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), now() + make_interval(days => 7)) ON CONFLICT (request_key) DO NOTHING",
                requestKey, subject, kind, sessionId, payloadHash, STATUS_PENDING, claimToken);
        return database.selectOne(SELECT + " WHERE request_key = ? AND expires_at > now()",
                PgConversationRequestRow.class, requestKey)
                .filter(row -> claimToken.equals(row.claimToken))
                .map(row -> Claim.CLAIMED)
                .orElse(Claim.EXISTS);
    }

    @Override
    public void succeed(String requestKey, String claimToken, String responseJson) {
        database.execute("UPDATE " + TABLE + " SET status = ?, response_json = ?, error_message = NULL "
                + "WHERE request_key = ? AND claim_token = ?", STATUS_READY, responseJson, requestKey, claimToken);
    }

    @Override
    public void fail(String requestKey, String claimToken, String errorMessage) {
        database.execute("UPDATE " + TABLE + " SET status = ?, error_message = ? "
                + "WHERE request_key = ? AND claim_token = ?", STATUS_FAILED, abbreviate(errorMessage), requestKey, claimToken);
    }
}
