package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.GatewayModelConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayProxyServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static byte[] json(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Test
    void routesByModelPrefixAndMergesExtraBody() throws Exception {
        var service = service(provider("DeepSeek", "deepseek", "https://api.deepseek.com/v1", "deepseek/", "deepseek-chat"));

        service.proxyChatCompletions(json(Map.of(
                "model", "deepseek/deepseek-chat",
                "messages", List.of(Map.of("role", "user", "content", "hi"))
        )));

        assertEquals("https://api.deepseek.com/v1/chat/completions", service.captured.uri);
        assertEquals("Bearer sk-test", service.captured.headers.get("Authorization"));
        var body = MAPPER.readValue(service.captured.body, MAP_TYPE);
        assertEquals("deepseek-chat", body.get("model"));
        assertEquals("strict", body.get("mode"));
    }

    @Test
    void routesAzureChatToDeploymentPath() throws Exception {
        var provider = provider("Azure", "azure", "https://example.openai.azure.com", "azure/", "gpt-4o");
        provider.apiVersion = "2025-01-01-preview";
        var service = service(provider);

        service.proxyChatCompletions(json(Map.of(
                "model", "azure/my-deployment",
                "messages", List.of(Map.of("role", "user", "content", "hi"))
        )));

        assertEquals("https://example.openai.azure.com/openai/deployments/my-deployment/chat/completions?api-version=2025-01-01-preview", service.captured.uri);
        assertEquals("sk-test", service.captured.headers.get("api-key"));
        var body = MAPPER.readValue(service.captured.body, MAP_TYPE);
        assertEquals("my-deployment", body.get("model"));
    }

    @Test
    void rejectsUnmatchedModel() {
        var service = service(provider("OpenAI", "openai", "https://api.openai.com/v1", "openai/", "gpt-4o"));

        assertThrows(BadRequestException.class, () -> service.proxyChatCompletions(json(Map.of(
                "model", "deepseek/deepseek-chat",
                "messages", List.of(Map.of("role", "user", "content", "hi"))
        ))));
    }

    @Test
    void routesByRegisteredModelBeforePrefixFallback() throws Exception {
        var provider = provider("LiteLLM", "litellm", "https://litellm.example.com", "litellm/", "deepseek/default");
        var service = service(provider, model("fast-chat", provider.id, "deepseek/deepseek-v4-flash", List.of("chat.completions")));

        service.proxyChatCompletions(json(Map.of(
                "model", "fast-chat",
                "messages", List.of(Map.of("role", "user", "content", "hi"))
        )));

        assertEquals("https://litellm.example.com/chat/completions", service.captured.uri);
        var body = MAPPER.readValue(service.captured.body, MAP_TYPE);
        assertEquals("deepseek/deepseek-v4-flash", body.get("model"));
    }

    @Test
    void registeredModelsBlockLegacyPrefixFallback() {
        var provider = provider("DeepSeek", "deepseek", "https://api.deepseek.com/v1", "deepseek/", "deepseek-chat");
        var service = service(provider, model("fast-chat", provider.id, "deepseek-chat", List.of("chat.completions")));

        assertThrows(BadRequestException.class, () -> service.proxyChatCompletions(json(Map.of(
                "model", "deepseek/deepseek-chat",
                "messages", List.of(Map.of("role", "user", "content", "hi"))
        ))));
    }

    @Test
    void publishedModelsUseSamePrioritySelectionAsRouting() {
        var slow = provider("Slow", "openai", "https://slow.example.com/v1", "", "slow-default");
        var fast = provider("Fast", "openai", "https://fast.example.com/v1", "", "fast-default");
        var routingEngine = routingEngine(List.of(slow, fast), List.of(
                model("shared-chat", slow.id, "slow-upstream", List.of("chat.completions"), 50L),
                model("shared-chat", fast.id, "fast-upstream", List.of("chat.completions"), 10L)
        ));

        var published = routingEngine.models();
        assertEquals(1, published.size());
        assertEquals("shared-chat", published.get(0).id());
        assertEquals("Fast", published.get(0).ownedBy());

        var route = routingEngine.route("shared-chat", GatewayEndpointType.CHAT_COMPLETIONS);
        assertEquals(fast.id, route.provider().id);
        assertEquals("fast-upstream", route.upstreamModel());
    }

    @Test
    void modelRegistryRespectsEndpointType() throws Exception {
        var provider = provider("LiteLLM", "litellm", "https://litellm.example.com", "litellm/", "deepseek/default");
        var service = service(provider, model("fast-response", provider.id, "deepseek/response-model", List.of("responses")));

        service.proxyResponses(json(Map.of(
                "model", "fast-response",
                "input", "hi"
        )));

        assertEquals("https://litellm.example.com/responses", service.captured.uri);
        var body = MAPPER.readValue(service.captured.body, MAP_TYPE);
        assertEquals("deepseek/response-model", body.get("model"));
    }

    @SuppressWarnings("unchecked")
    private CapturingGatewayProxyService service(GatewayProviderConfig provider) {
        return service(provider, List.of());
    }

    @SuppressWarnings("unchecked")
    private CapturingGatewayProxyService service(GatewayProviderConfig provider, GatewayModelConfig model) {
        return service(provider, List.of(model));
    }

    @SuppressWarnings("unchecked")
    private CapturingGatewayProxyService service(GatewayProviderConfig provider, List<GatewayModelConfig> models) {
        var service = new CapturingGatewayProxyService();
        service.routingEngine = routingEngine(List.of(provider), models);
        service.secretProtector = new GatewaySecretProtector("test-secret");
        return service;
    }

    @SuppressWarnings("unchecked")
    private GatewayRoutingEngine routingEngine(List<GatewayProviderConfig> providers, List<GatewayModelConfig> models) {
        var routingEngine = new GatewayRoutingEngine();
        routingEngine.gatewayProviderCollection = (MongoCollection<GatewayProviderConfig>) mock(MongoCollection.class);
        routingEngine.gatewayModelCollection = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);
        when(routingEngine.gatewayProviderCollection.find(any(Query.class))).thenReturn(providers);
        when(routingEngine.gatewayModelCollection.find(any(Query.class))).thenReturn(models);
        return routingEngine;
    }

    private GatewayProviderConfig provider(String name, String type, String baseUrl, String prefix, String defaultModel) {
        var provider = new GatewayProviderConfig();
        provider.id = name.toLowerCase(Locale.ROOT);
        provider.name = name;
        provider.type = type;
        provider.baseUrl = baseUrl;
        provider.enabled = true;
        provider.allowPrivateNetwork = true;
        provider.modelPrefix = prefix;
        provider.defaultChatModel = defaultModel;
        provider.apiKeyEncrypted = new GatewaySecretProtector("test-secret").protect("sk-test");
        provider.requestExtraBody = "{\"mode\":\"strict\"}";
        return provider;
    }

    private GatewayModelConfig model(String modelId, String providerId, String upstreamModel, List<String> endpointTypes) {
        return model(modelId, providerId, upstreamModel, endpointTypes, 100L);
    }

    private GatewayModelConfig model(String modelId, String providerId, String upstreamModel, List<String> endpointTypes, long priority) {
        var model = new GatewayModelConfig();
        model.id = modelId;
        model.modelId = modelId;
        model.providerId = providerId;
        model.upstreamModel = upstreamModel;
        model.endpointTypes = endpointTypes;
        model.enabled = true;
        model.priority = priority;
        return model;
    }

    private static final class CapturingGatewayProxyService extends GatewayProxyService {
        HTTPRequest captured;

        @Override
        HTTPResponse execute(HTTPRequest request, GatewayProviderConfig provider) {
            captured = request;
            return new HTTPResponse(200, Map.of("Content-Type", "application/json"), "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        }
    }
}
