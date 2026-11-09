package ai.core.server.web.auth;

import ai.core.server.domain.User;
import com.mongodb.MongoClientSettings;
import core.framework.mongo.MongoCollection;
import core.framework.web.Request;
import core.framework.web.exception.UnauthorizedException;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestAuthenticatorTest {
    @Test
    void apiKeyAuthorizationMapsAnthropicHeaderToBearer() {
        var request = mock(Request.class);
        when(request.header("Authorization")).thenReturn(Optional.empty());
        when(request.header("x-api-key")).thenReturn(Optional.of(" coreai_gateway_key "));

        assertEquals("Bearer coreai_gateway_key", RequestAuthenticator.apiKeyAuthorization(request));
    }

    @Test
    void apiKeyAuthorizationNeverOverridesAnExistingAuthorizationHeader() {
        var request = mock(Request.class);
        when(request.header("Authorization")).thenReturn(Optional.of("Bearer coreai_session_key"));
        when(request.header("x-api-key")).thenReturn(Optional.of("coreai_gateway_key"));

        assertNull(RequestAuthenticator.apiKeyAuthorization(request));
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyApiKeyLookupMatchesStringOnlyPartialIndex() {
        var users = (MongoCollection<User>) mock(MongoCollection.class);
        var request = mock(Request.class);
        when(request.header("X-Auth-Request-Email")).thenReturn(Optional.empty());
        when(request.header("Authorization")).thenReturn(Optional.of("Bearer coreai_legacy-test-key"));
        when(users.findOne(any(Bson.class))).thenReturn(Optional.empty());

        var authenticator = new RequestAuthenticator();
        authenticator.userStore = ai.core.server.user.MongoUserStore.of(users);

        assertThrows(UnauthorizedException.class, () -> authenticator.authenticate(request));

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(users).findOne(filter.capture());
        var actual = filter.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        var expected = BsonDocument.parse("""
            {"$and":[{"api_key":"coreai_legacy-test-key"},{"api_key":{"$type":"string"}}]}
            """);
        assertEquals(expected, actual);
    }
}
