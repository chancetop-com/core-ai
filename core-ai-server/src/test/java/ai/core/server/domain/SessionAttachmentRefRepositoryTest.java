package ai.core.server.domain;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Filters;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.regex.Pattern;

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

    @SuppressWarnings("unchecked")
    @Test
    void findsOwnedReferencesByIdPrefix() {
        var collection = (MongoCollection<SessionAttachmentRef>) mock(MongoCollection.class);
        var repository = new SessionAttachmentRefRepository();
        repository.collection = collection;
        var clip = new SessionAttachmentRef();
        clip.id = "video_7bb80432-cf17-4742-933b-5049d55ce644";
        when(collection.find(any(Bson.class))).thenReturn(List.of(clip));

        var result = repository.findOwnedByPrefix("video_7bb80432", "session-1", "user-1");

        assertEquals(List.of(clip), result);
        var captor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).find(captor.capture());
        var expected = Filters.and(
                Filters.regex("_id", Pattern.compile("^" + Pattern.quote("video_7bb80432"))),
                Filters.eq("session_id", "session-1"),
                Filters.eq("user_id", "user-1"));
        assertEquals(expected.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()),
                captor.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()));
    }

    @SuppressWarnings("unchecked")
    @Test
    void findsOwnedVideosNewestFirst() {
        var collection = (MongoCollection<SessionAttachmentRef>) mock(MongoCollection.class);
        var repository = new SessionAttachmentRefRepository();
        repository.collection = collection;
        when(collection.find(any(Query.class))).thenReturn(List.of());

        repository.findOwnedVideos("session-1", "user-1");

        var captor = ArgumentCaptor.forClass(Query.class);
        verify(collection).find(captor.capture());
        assertBsonEquals("""
                {"$and":[
                  {"session_id":"session-1"},
                  {"user_id":"user-1"},
                  {"kind":"VIDEO"}
                ]}
                """, captor.getValue().filter);
        assertBsonEquals("{\"created_at\":-1}", captor.getValue().sort);
    }

    @SuppressWarnings("unchecked")
    @Test
    void attachesTheFileBehindAReference() {
        var collection = (MongoCollection<SessionAttachmentRef>) mock(MongoCollection.class);
        var repository = new SessionAttachmentRefRepository();
        repository.collection = collection;

        repository.attachFile("video_1", "file-1");

        var filter = ArgumentCaptor.forClass(Bson.class);
        var update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).update(filter.capture(), update.capture());
        assertBsonEquals("{\"_id\":\"video_1\"}", filter.getValue());
        assertBsonEquals("{\"$set\":{\"file_id\":\"file-1\"}}", update.getValue());
    }

    private void assertBsonEquals(String expected, Bson actual) {
        assertEquals(BsonDocument.parse(expected),
                actual.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()));
    }
}
