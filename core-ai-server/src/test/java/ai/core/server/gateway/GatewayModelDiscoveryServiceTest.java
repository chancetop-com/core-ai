package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.User;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayModelDiscoveryServiceTest {
    @Test
    void discoversOpenAICompatibleModels() {
        var provider = provider("provider-1", "openai", "https://api.example.com/v1");
        var service = service(provider, """
                {"data":[{"id":"gpt-4.1"},{"id":"text-embedding-3-small"}]}
                """);

        var response = service.discover("provider-1", "admin-1");

        assertEquals("provider-1", response.providerId);
        assertEquals(1, response.models.size());
        assertEquals("gpt-4.1", response.models.get(0).id);
        assertEquals(List.of("chat.completions", "responses"), response.models.get(0).endpointTypes);
        assertFalse(response.models.get(0).imported);
    }

    @Test
    void discoversLiteLLMModelInfoMetadata() {
        var provider = provider("provider-1", "litellm", "https://litellm.example.com");
        var service = service(provider, """
                {"data":[{"model_name":"fast-chat","model_info":{
                  "max_tokens":128000,
                  "supports_function_calling":true,
                  "supports_vision":true,
                  "input_cost_per_token":0.000001,
                  "output_cost_per_token":0.000002
                }}]}
                """);

        var response = service.discover("provider-1", "admin-1");

        assertEquals(1, response.models.size());
        var model = response.models.get(0);
        assertEquals("fast-chat", model.id);
        assertEquals(128000L, model.contextWindow);
        assertTrue(model.supportsTools);
        assertTrue(model.supportsVision);
        assertEquals(1D, model.inputPricePer1MTokens);
        assertEquals(2D, model.outputPricePer1MTokens);
    }

    @SuppressWarnings("unchecked")
    private CapturingGatewayModelDiscoveryService service(GatewayProviderConfig provider, String responseBody) {
        var service = new CapturingGatewayModelDiscoveryService(responseBody);
        service.gatewayProviderCollection = (MongoCollection<GatewayProviderConfig>) mock(MongoCollection.class);
        service.gatewayModelCollection = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);
        service.userCollection = (MongoCollection<User>) mock(MongoCollection.class);
        service.secretProtector = new GatewaySecretProtector("test-secret");
        when(service.gatewayProviderCollection.get(provider.id)).thenReturn(Optional.of(provider));
        when(service.gatewayModelCollection.find(any(Query.class))).thenReturn(List.of());
        var admin = new User();
        admin.id = "admin-1";
        admin.role = "admin";
        when(service.userCollection.get("admin-1")).thenReturn(Optional.of(admin));
        return service;
    }

    private GatewayProviderConfig provider(String id, String type, String baseUrl) {
        var provider = new GatewayProviderConfig();
        provider.id = id;
        provider.name = "Provider";
        provider.type = type;
        provider.baseUrl = baseUrl;
        provider.enabled = true;
        provider.allowPrivateNetwork = true;
        provider.apiKeyEncrypted = new GatewaySecretProtector("test-secret").protect("sk-test");
        return provider;
    }

    private static final class CapturingGatewayModelDiscoveryService extends GatewayModelDiscoveryService {
        final String responseBody;
        HTTPRequest captured;

        CapturingGatewayModelDiscoveryService(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        HTTPResponse execute(HTTPRequest request, GatewayProviderConfig provider) {
            captured = request;
            return new HTTPResponse(200, Map.of("Content-Type", "application/json"), responseBody.getBytes(StandardCharsets.UTF_8));
        }
    }
}
