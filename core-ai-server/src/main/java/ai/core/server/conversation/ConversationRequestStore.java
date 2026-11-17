package ai.core.server.conversation;

import java.util.Optional;

/**
 * Durable idempotency record for direct-conversation commands. The claim step is atomic:
 * exactly one caller sees {@link Claim#CLAIMED} for a request key, the rest observe the stored
 * receipt. Expired rows fall out of lookups and may be claimed again, which is safe because the
 * client generates a fresh key for a genuinely new intent.
 *
 * @author core-ai
 */
public interface ConversationRequestStore {
    String STATUS_PENDING = "PENDING";
    String STATUS_READY = "READY";
    String STATUS_FAILED = "FAILED";

    Optional<Receipt> find(String requestKey);

    Claim claim(String requestKey, String subject, String kind, String sessionId, String payloadHash, String claimToken);

    void succeed(String requestKey, String claimToken, String responseJson);

    void fail(String requestKey, String claimToken, String errorMessage);

    enum Claim {
        CLAIMED,
        EXISTS
    }

    record Receipt(String subject, String kind, String sessionId, String payloadHash,
                   String status, String responseJson, String errorMessage, String claimToken) {
    }
}
