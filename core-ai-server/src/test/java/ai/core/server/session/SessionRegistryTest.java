package ai.core.server.session;

import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import com.mongodb.MongoWriteException;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.bson.BsonDocument;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.BsonValueCodecProvider;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRegistryTest {
    @Test
    void createPersistsCompleteSessionIdentityBeforeReturning() {
        var registry = registry();
        var registration = new SessionRegistry.SessionRegistration(
                "s-1", "user-1", "agent-1", "a2a", "schedule-1", "key-1");

        var created = registry.create(registration);

        var captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(registry.chatSessionCollection).insert(captor.capture());
        var persisted = captor.getValue();
        assertSame(persisted, created);
        assertEquals("s-1", persisted.id);
        assertEquals("user-1", persisted.userId);
        assertEquals("agent-1", persisted.agentId);
        assertEquals("a2a", persisted.source);
        assertEquals("schedule-1", persisted.scheduleId);
        assertEquals("key-1", persisted.apiKeyId);
        assertEquals(0L, persisted.messageCount);
        assertNotNull(persisted.createdAt);
    }

    @Test
    void createAcceptsDuplicateOnlyWhenPersistedIdentityMatches() {
        var registry = registry();
        var duplicate = mock(MongoWriteException.class);
        when(duplicate.getCode()).thenReturn(11000);
        var registration = new SessionRegistry.SessionRegistration(
                "s-1", "user-1", "agent-1", "chat", null, null);
        var existing = session("s-1", "user-1", "agent-1");
        existing.source = "chat";
        when(registry.chatSessionCollection.get("s-1")).thenReturn(Optional.of(existing));
        org.mockito.Mockito.doThrow(duplicate).when(registry.chatSessionCollection).insert(any(ChatSession.class));

        assertSame(existing, registry.create(registration));
    }

    @Test
    void createRejectsDuplicateWithDifferentIdentity() {
        var registry = registry();
        var duplicate = mock(MongoWriteException.class);
        when(duplicate.getCode()).thenReturn(11000);
        var registration = new SessionRegistry.SessionRegistration(
                "s-1", "user-1", "agent-1", "chat", null, null);
        when(registry.chatSessionCollection.get("s-1"))
                .thenReturn(Optional.of(session("s-1", "another-user", "agent-1")));
        org.mockito.Mockito.doThrow(duplicate).when(registry.chatSessionCollection).insert(any(ChatSession.class));

        assertThrows(IllegalStateException.class, () -> registry.create(registration));
    }

    @Test
    void createPropagatesNonDuplicateMongoFailure() {
        var registry = registry();
        var failure = mock(MongoWriteException.class);
        when(failure.getCode()).thenReturn(91);
        org.mockito.Mockito.doThrow(failure).when(registry.chatSessionCollection).insert(any(ChatSession.class));

        assertThrows(MongoWriteException.class, () -> registry.create(
                new SessionRegistry.SessionRegistration("s-1", "user-1", null, "chat", null, null)));
    }

    @Test
    void requireAccessibleEnforcesExistenceDeletionAndOwner() {
        var registry = registry();
        var active = session("active", "user-1", "agent-1");
        var deleted = session("deleted", "user-1", "agent-1");
        deleted.deletedAt = ZonedDateTime.now();
        when(registry.chatSessionCollection.get("active")).thenReturn(Optional.of(active));
        when(registry.chatSessionCollection.get("deleted")).thenReturn(Optional.of(deleted));
        when(registry.chatSessionCollection.get("missing")).thenReturn(Optional.empty());

        assertSame(active, registry.requireAccessible("active", "user-1"));
        assertThrows(ForbiddenException.class, () -> registry.requireAccessible("active", "user-2"));
        assertThrows(ForbiddenException.class, () -> registry.requireAccessible("active", " "));
        assertThrows(NotFoundException.class, () -> registry.requireAccessible("deleted", "user-1"));
        assertThrows(NotFoundException.class, () -> registry.requireAccessible("missing", "user-1"));
    }

    @Test
    void recordUserMessageUpdatesExistingRowWithoutCreatingStub() {
        var registry = registry();
        when(registry.chatSessionCollection.update(any(Bson.class), any(Bson.class))).thenReturn(1L);

        registry.recordUserMessage("s-1", "  hello   registry  ");

        var captor = ArgumentCaptor.forClass(Bson.class);
        verify(registry.chatSessionCollection).update(any(Bson.class), captor.capture());
        var update = bson(captor.getValue());
        assertEquals("hello registry", update.getDocument("$set").getString("title").getValue());
        assertTrue(update.getDocument("$set").containsKey("last_message_at"));
        assertEquals(1L, update.getDocument("$inc").getInt64("message_count").getValue());
        verify(registry.chatSessionCollection, never()).insert(any(ChatSession.class));
    }

    @Test
    void dependencyUpdateFailsWhenRegistryRowIsMissingInsteadOfInsertingStub() {
        var registry = registry();
        when(registry.chatSessionCollection.update(any(Bson.class), any(Bson.class))).thenReturn(0L);
        var tool = ToolRef.of("tool-1", ToolSourceType.BUILTIN, null);

        assertThrows(IllegalStateException.class,
                () -> registry.addLoadedTools("missing", List.of(tool)));

        verify(registry.chatSessionCollection, never()).insert(any(ChatSession.class));
    }

    private SessionRegistry registry() {
        var registry = new SessionRegistry();
        @SuppressWarnings("unchecked")
        MongoCollection<ChatSession> collection = mock(MongoCollection.class);
        registry.chatSessionCollection = collection;
        return registry;
    }

    private ChatSession session(String id, String userId, String agentId) {
        var session = new ChatSession();
        session.id = id;
        session.userId = userId;
        session.agentId = agentId;
        return session;
    }

    private BsonDocument bson(Bson value) {
        return value.toBsonDocument(BsonDocument.class,
                CodecRegistries.fromRegistries(
                        CodecRegistries.fromCodecs(new ZonedDateTimeCodec()),
                        CodecRegistries.fromProviders(new ValueCodecProvider(), new BsonValueCodecProvider())));
    }

    private static final class ZonedDateTimeCodec implements Codec<ZonedDateTime> {
        @Override
        public ZonedDateTime decode(BsonReader reader, DecoderContext decoderContext) {
            return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(reader.readDateTime()), ZoneOffset.UTC);
        }

        @Override
        public void encode(BsonWriter writer, ZonedDateTime value, EncoderContext encoderContext) {
            writer.writeDateTime(value.toInstant().toEpochMilli());
        }

        @Override
        public Class<ZonedDateTime> getEncoderClass() {
            return ZonedDateTime.class;
        }
    }
}
