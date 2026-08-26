package ai.core.server.apiuser;

import ai.core.server.domain.User;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Filters;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.impl.ZonedDateTimeCodec;
import org.bson.BsonDocument;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiUserQuotaServiceTest {
    private static final CodecRegistry REGISTRY = CodecRegistries.fromRegistries(
            CodecRegistries.fromCodecs(new ZonedDateTimeCodec()),
            MongoClientSettings.getDefaultCodecRegistry());

    @Test
    void resetQuotaClearsConsumedCountersAndAdvancesWindow() {
        var collection = mockCollection();
        var service = new ApiUserQuotaService();
        service.userCollection = collection;

        service.resetQuota("user-1");

        assertWindowReset(collection);
    }

    @Test
    void checkQuotaLazilyResetsWindowWhenDayChanges() {
        var collection = mockCollection();
        var service = new ApiUserQuotaService();
        service.userCollection = collection;

        var user = new User();
        user.id = "user-1";
        user.quotaInputTokens = 1_000L;
        user.quotaConsumedInputTokens = 1_000L;
        user.quotaWindowStart = ZonedDateTime.now().minusDays(1);
        when(collection.get("user-1")).thenReturn(Optional.of(user));

        service.checkQuota("user-1");

        assertWindowReset(collection);
    }

    @Test
    void checkQuotaRejectsWhenConsumedAtLimit() {
        var collection = mockCollection();
        var service = new ApiUserQuotaService();
        service.userCollection = collection;

        var user = new User();
        user.id = "user-1";
        user.quotaInputTokens = 1_000L;
        user.quotaConsumedInputTokens = 1_000L;
        user.quotaWindowStart = ZonedDateTime.now();
        when(collection.get("user-1")).thenReturn(Optional.of(user));

        assertThrows(QuotaExceededException.class, () -> service.checkQuota("user-1"));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<User> mockCollection() {
        return mock(MongoCollection.class);
    }

    private void assertWindowReset(MongoCollection<User> collection) {
        var captor = ArgumentCaptor.forClass(org.bson.conversions.Bson.class);
        verify(collection).update(eq(Filters.eq("_id", "user-1")), captor.capture());
        var update = captor.getValue().toBsonDocument(BsonDocument.class, REGISTRY);
        var set = update.getDocument("$set");
        assertEquals(0L, set.get("quota_consumed_input_tokens").asInt64().getValue());
        assertEquals(0L, set.get("quota_consumed_output_tokens").asInt64().getValue());
        assertTrue(set.containsKey("quota_window_start"));
    }
}
