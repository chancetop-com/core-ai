package ai.core.server.session;

import ai.core.server.domain.ChatMessage;
import com.mongodb.MongoClientSettings;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class MongoChatMessageStoreTest {
    @SuppressWarnings("unchecked")
    private final MongoCollection<ChatMessage> collection = mock(MongoCollection.class);
    private final MongoChatMessageStore store = MongoChatMessageStore.of(collection);

    @Test
    void appliesTheSameInclusiveBoundaryToFindAndCount() {
        when(collection.find(any(Query.class))).thenReturn(List.of());
        var query = new ChatMessageQuery();
        query.sessionId = "shared-session";
        query.maxSeq = 2L;
        query.offset = 50;
        query.limit = 25;

        store.find(query);
        store.count(query);

        var find = ArgumentCaptor.forClass(Query.class);
        var count = ArgumentCaptor.forClass(Bson.class);
        verify(collection).find(find.capture());
        verify(collection).count(count.capture());
        var filter = fields(find.getValue().filter);
        assertEquals("shared-session", filter.get("session_id").asString().getValue());
        assertEquals(2L, filter.get("seq").asDocument().getInt64("$lte").getValue());
        assertEquals(filter, fields(count.getValue()));
        assertEquals(50, find.getValue().skip);
        assertEquals(25, find.getValue().limit);
    }

    @Test
    void doesNotAddBoundaryToExistingHistoryReaders() {
        when(collection.find(any(Query.class))).thenReturn(List.of());
        var query = new ChatMessageQuery();
        query.sessionId = "session";
        store.find(query);

        var find = ArgumentCaptor.forClass(Query.class);
        verify(collection).find(find.capture());
        var filter = find.getValue().filter.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry());
        assertFalse(filter.containsKey("seq"));
    }

    private Map<String, BsonValue> fields(Bson filter) {
        var document = filter.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry());
        if (!document.containsKey("$and")) return document;
        var result = new HashMap<String, BsonValue>();
        document.getArray("$and").forEach(clause -> result.putAll(clause.asDocument()));
        return result;
    }
}
