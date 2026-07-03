package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayProxyServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
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

    @SuppressWarnings("unchecked")
    private CapturingGatewayProxyService service(GatewayProviderConfig provider) {
        var service = new CapturingGatewayProxyService();
        service.gatewayProviderCollection = (MongoCollection<GatewayProviderConfig>) mock(MongoCollection.class);
        service.secretProtector = new GatewaySecretProtector("test-secret");
        when(service.gatewayProviderCollection.find(any(Query.class))).thenReturn(List.of(provider));
        return service;
    }

    private GatewayProviderConfig provider(String name, String type, String baseUrl, String prefix, String defaultModel) {
        var provider = new GatewayProviderConfig();
        provider.id = name.toLowerCase();
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

    private static byte[] json(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
