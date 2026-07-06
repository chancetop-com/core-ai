package ai.core.server.session;

import ai.core.server.domain.ChatSession;
import org.bson.BsonDocument;
import org.bson.codecs.BsonValueCodecProvider;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageServiceTest {
    @Test
    void updateSessionTitleSucceedsForOwner() {
        var service = new ChatMessageService();
        service.chatSessionCollection = mock();
        var session = session("s-1", "user-1");
        when(service.chatSessionCollection.get("s-1")).thenReturn(Optional.of(session));
        when(service.chatSessionCollection.update(any(Bson.class), any(Bson.class))).thenReturn(1L);

        var ok = service.updateSessionTitle("user-1", "s-1", "  My  renamed  chat ");

        assertTrue(ok);
        var captor = ArgumentCaptor.forClass(Bson.class);
        verify(service.chatSessionCollection).update(any(Bson.class), captor.capture());
        var doc = captor.getValue().toBsonDocument(BsonDocument.class,
            CodecRegistries.fromProviders(new ValueCodecProvider(), new BsonValueCodecProvider()));
        assertEquals("My renamed chat", doc.getDocument("$set").getString("title").getValue());
    }

    @Test
    void updateSessionTitleReturnsFalseWhenMissing() {
        var service = new ChatMessageService();
        service.chatSessionCollection = mock();
        when(service.chatSessionCollection.get("missing")).thenReturn(Optional.empty());

        var ok = service.updateSessionTitle("user-1", "missing", "title");

        assertFalse(ok);
        verify(service.chatSessionCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void updateSessionTitleReturnsFalseForNonOwner() {
        var service = new ChatMessageService();
        service.chatSessionCollection = mock();
        var session = session("s-1", "owner");
        when(service.chatSessionCollection.get("s-1")).thenReturn(Optional.of(session));

        var ok = service.updateSessionTitle("intruder", "s-1", "title");

        assertFalse(ok);
        verify(service.chatSessionCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void updateSessionTitleReturnsFalseForBlank() {
        var service = new ChatMessageService();
        service.chatSessionCollection = mock();
        var session = session("s-1", "user-1");
        when(service.chatSessionCollection.get("s-1")).thenReturn(Optional.of(session));

        var ok = service.updateSessionTitle("user-1", "s-1", "   ");

        assertFalse(ok);
        verify(service.chatSessionCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void updateSessionTitleCapsLength() {
        var service = new ChatMessageService();
        service.chatSessionCollection = mock();
        var session = session("s-1", "user-1");
        when(service.chatSessionCollection.get("s-1")).thenReturn(Optional.of(session));
        when(service.chatSessionCollection.update(any(Bson.class), any(Bson.class))).thenReturn(1L);

        var ok = service.updateSessionTitle("user-1", "s-1", "x".repeat(200));

        assertTrue(ok);
        var captor = ArgumentCaptor.forClass(Bson.class);
        verify(service.chatSessionCollection).update(any(Bson.class), captor.capture());
        var doc = captor.getValue().toBsonDocument(BsonDocument.class,
            CodecRegistries.fromProviders(new ValueCodecProvider(), new BsonValueCodecProvider()));
        assertEquals("x".repeat(100), doc.getDocument("$set").getString("title").getValue());
    }

    @Test
    void batchSoftDeleteReturnsOnlyOwnedExistingIds() {
        var service = new ChatMessageService();
        service.chatSessionCollection = mock();
        when(service.chatSessionCollection.get("owned")).thenReturn(Optional.of(session("owned", "user-1")));
        when(service.chatSessionCollection.get("foreign")).thenReturn(Optional.of(session("foreign", "user-2")));
        when(service.chatSessionCollection.get("missing")).thenReturn(Optional.empty());
        when(service.chatSessionCollection.update(any(Bson.class), any(Bson.class))).thenReturn(1L);

        var deletedIds = service.batchSoftDelete("user-1", List.of("owned", "foreign", "missing"));

        // Non-owned and missing ids must not leak into the result: callers run
        // follow-up cleanup (e.g. sandbox snapshot deletion) on the returned ids.
        assertEquals(List.of("owned"), deletedIds);
        verify(service.chatSessionCollection, times(1)).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void updateSessionTitleReturnsFalseForDeletedSession() {
        var service = new ChatMessageService();
        service.chatSessionCollection = mock();
        var session = session("s-1", "user-1");
        session.deletedAt = ZonedDateTime.now();
        when(service.chatSessionCollection.get("s-1")).thenReturn(Optional.of(session));

        var ok = service.updateSessionTitle("user-1", "s-1", "new title");

        assertFalse(ok);
        verify(service.chatSessionCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    private ChatSession session(String id, String userId) {
        var s = new ChatSession();
        s.id = id;
        s.userId = userId;
        return s;
    }
}
