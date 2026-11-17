package ai.core.server.conversation;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Fallback for single-replica/test deployments without PostgreSQL. Receipts are process-local,
 * so an ACK lost across a restart is reported as unknown rather than duplicated.
 *
 * @author core-ai
 */
public class InMemoryConversationRequestStore implements ConversationRequestStore {
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<Receipt> find(String requestKey) {
        var entry = entries.get(requestKey);
        if (entry == null || entry.expiresAt <= System.currentTimeMillis()) return Optional.empty();
        return Optional.of(entry.receipt());
    }

    @Override
    public Claim claim(String requestKey, String subject, String kind, String sessionId, String payloadHash, String claimToken) {
        var now = System.currentTimeMillis();
        var fresh = new Entry(new Receipt(subject, kind, sessionId, payloadHash, STATUS_PENDING, null, null, claimToken),
                now + 7L * 24 * 60 * 60 * 1000);
        var existing = entries.compute(requestKey, (key, current) ->
                current == null || current.expiresAt <= now ? fresh : current);
        return java.util.Objects.equals(existing, fresh) ? Claim.CLAIMED : Claim.EXISTS;
    }

    @Override
    public void succeed(String requestKey, String claimToken, String responseJson) {
        update(requestKey, claimToken, STATUS_READY, responseJson, null);
    }

    @Override
    public void fail(String requestKey, String claimToken, String errorMessage) {
        update(requestKey, claimToken, STATUS_FAILED, null, errorMessage);
    }

    private void update(String requestKey, String claimToken, String status, String responseJson, String errorMessage) {
        entries.computeIfPresent(requestKey, (key, current) -> {
            if (!current.receipt().claimToken().equals(claimToken)) return current;
            var receipt = current.receipt();
            return new Entry(new Receipt(receipt.subject(), receipt.kind(), receipt.sessionId(), receipt.payloadHash(),
                    status, responseJson, errorMessage, claimToken), current.expiresAt);
        });
    }

    private record Entry(Receipt receipt, long expiresAt) {
    }
}
