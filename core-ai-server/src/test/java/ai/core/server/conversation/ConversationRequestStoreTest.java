package ai.core.server.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationRequestStoreTest {
    @Test
    void requestKeyIsStableAndScoped() {
        var key = ConversationRequestKeys.requestKey("user-1", "send", "session-1", "client-1");
        assertEquals(key, ConversationRequestKeys.requestKey("user-1", "send", "session-1", "client-1"));
        assertNotEquals(key, ConversationRequestKeys.requestKey("user-2", "send", "session-1", "client-1"));
        assertNotEquals(key, ConversationRequestKeys.requestKey("user-1", "create", "session-1", "client-1"));
        assertNotEquals(key, ConversationRequestKeys.requestKey("user-1", "send", "session-1", "client-2"));
    }

    @Test
    void onlyTheFirstClaimWinsAndReadyResultIsReplayed() {
        var store = new InMemoryConversationRequestStore();
        var token = "claim-1";

        assertEquals(ConversationRequestStore.Claim.CLAIMED,
                store.claim("key", "user-1", "send", "session-1", "hash", token));
        assertEquals(ConversationRequestStore.Claim.EXISTS,
                store.claim("key", "user-1", "send", "session-1", "hash", "claim-2"));

        store.succeed("key", token, "{\"ok\":true}");

        var receipt = store.find("key").orElseThrow();
        assertEquals(ConversationRequestStore.STATUS_READY, receipt.status());
        assertEquals("{\"ok\":true}", receipt.responseJson());
        assertEquals("session-1", receipt.sessionId());
    }

    @Test
    void failureKeepsTheReceiptButDoesNotBecomeReady() {
        var store = new InMemoryConversationRequestStore();
        store.claim("key", "user-1", "create", null, "hash", "claim-1");
        store.fail("key", "claim-1", "agent unavailable");

        var receipt = store.find("key").orElseThrow();
        assertEquals(ConversationRequestStore.STATUS_FAILED, receipt.status());
        assertEquals("agent unavailable", receipt.errorMessage());
        assertNull(receipt.responseJson());
    }

    @Test
    void aDifferentClaimTokenCannotCompleteSomeoneElsesRequest() {
        var store = new InMemoryConversationRequestStore();
        store.claim("key", "user-1", "send", "session-1", "hash", "claim-1");
        store.succeed("key", "claim-2", "{\"ok\":true}");

        assertEquals(ConversationRequestStore.STATUS_PENDING, store.find("key").orElseThrow().status());
    }
}
