package ai.core.server.share;

import ai.core.server.domain.SessionShare;
import ai.core.server.domain.SessionShareFile;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.impl.MongoImpl;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class MongoSessionShareStoreTest {
    @SuppressWarnings("unchecked")
    private final MongoCollection<SessionShare> collection = mock(MongoCollection.class);
    private final MongoSessionShareStore store = new MongoSessionShareStore();

    @BeforeEach
    void wire() {
        store.collection = collection;
    }

    @Test
    void registersConcreteManifestTypesAndSessionIdentity() {
        var mongo = new MongoImpl();
        assertNotNull(mongo.collection(SessionShare.class));
        mongo.view(SessionShareFile.class);
    }

    @Test
    void duplicateSessionOrTokenCannotReplaceTheWinner() {
        var share = share();
        var duplicate = mock(MongoWriteException.class);
        when(duplicate.getCode()).thenReturn(11000);
        doThrow(duplicate).when(collection).insert(share);

        assertFalse(store.replace(share, null));
    }

    @Test
    void replacementRequiresBothSessionAndPreviousGeneration() {
        var share = share();
        when(collection.update(any(Bson.class), any(Bson.class))).thenReturn(1L);
        assertTrue(store.replace(share, "old-generation"));

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).update(filter.capture(), any(Bson.class));
        var document = fields(filter.getValue());
        assertEquals("session-1", document.get("_id").asString().getValue());
        assertEquals("old-generation", document.get("share_id").asString().getValue());
    }

    @Test
    void staleGenerationDoesNotReportSuccessfulReplacement() {
        when(collection.update(any(Bson.class), any(Bson.class))).thenReturn(0L);
        assertFalse(store.replace(share(), "old-generation"));
    }

    @Test
    void revokeMatchesTheGenerationAndLeavesPreviousRevocationTimeIntact() {
        store.revoke("session-1", "generation-1", ZonedDateTime.now());

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).update(filter.capture(), any(Bson.class));
        var document = fields(filter.getValue());
        assertEquals("session-1", document.get("_id").asString().getValue());
        assertEquals("generation-1", document.get("share_id").asString().getValue());
        assertTrue(document.get("revoked_at").isNull());
    }

    private SessionShare share() {
        var share = new SessionShare();
        share.sessionId = "session-1";
        share.id = "new-generation";
        return share;
    }

    private Map<String, BsonValue> fields(Bson filter) {
        var document = filter.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry());
        if (!document.containsKey("$and")) return document;
        var result = new HashMap<String, BsonValue>();
        document.getArray("$and").forEach(clause -> result.putAll(clause.asDocument()));
        return result;
    }
}
