package ai.core.server.domain;

import com.mongodb.MongoClientSettings;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionAttachmentRefRepositoryTest {
    @SuppressWarnings("unchecked")
    @Test
    void findsSandboxAttachmentsByOwnerNewestFirst() {
        var collection = (MongoCollection<SessionAttachmentRef>) mock(MongoCollection.class);
        var repository = new SessionAttachmentRefRepository();
        repository.collection = collection;
        var newest = new SessionAttachmentRef();
        newest.id = "new";
        when(collection.find(any(Query.class))).thenReturn(List.of(newest));

        var result = repository.findSandboxAttachments("session-1", "user-1");

        assertEquals(List.of(newest), result);
        var captor = ArgumentCaptor.forClass(Query.class);
        verify(collection).find(captor.capture());
        var query = captor.getValue();
        assertBsonEquals("""
                {"$and":[
                  {"session_id":"session-1"},
                  {"user_id":"user-1"},
                  {"kind":"SANDBOX"}
                ]}
                """, query.filter);
        assertBsonEquals("{\"created_at\":-1}", query.sort);
    }

    private void assertBsonEquals(String expected, org.bson.conversions.Bson actual) {
        assertEquals(BsonDocument.parse(expected),
                actual.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()));
    }
}
