package ai.core.server.gateway;

import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayLLMProviderTest {
    @Test
    void routesGatewayModelIdToUpstreamModel() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var gateway = gateway(List.of(provider), List.of(model("fast-chat", provider.id, "deepseek/deepseek-v4-flash")), null);

        var response = gateway.completion(request("fast-chat"));

        assertEquals("ok", response.choices.getFirst().message.content);
        assertEquals("deepseek/deepseek-v4-flash", gateway.upstream.captured.model);
        assertEquals("https://litellm.example.com", gateway.upstreamBaseUrl);
    }

    @Test
    void fallsBackToStaticProviderWhenNoGatewayProviders() {
        var fallback = new CapturingLiteLLMProvider(new LLMProviderConfig(null, null, null), "https://static.example.com", "answered by fallback");
        var gateway = gateway(List.of(), List.of(), fallback);

        var response = gateway.completion(request("gpt-4o"));

        assertEquals("answered by fallback", response.choices.getFirst().message.content);
        assertEquals("gpt-4o", fallback.captured.model);
    }

    @Test
    void fallsBackToStaticProviderWhenModelNotRegistered() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var fallback = new CapturingLiteLLMProvider(new LLMProviderConfig(null, null, null), "https://static.example.com", "answered by fallback");
        var gateway = gateway(List.of(provider), List.of(model("fast-chat", provider.id, "deepseek/deepseek-v4-flash")), fallback);

        var response = gateway.completion(request("legacy-model"));

        assertEquals("answered by fallback", response.choices.getFirst().message.content);
        assertEquals("legacy-model", fallback.captured.model);
    }

    @Test
    void throwsWhenNoRouteAndNoFallback() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var gateway = gateway(List.of(provider), List.of(model("fast-chat", provider.id, "deepseek/deepseek-v4-flash")), null);

        assertThrows(BadRequestException.class, () -> gateway.completion(request("unknown-model")));
    }

    @Test
    void rejectsAzureGatewayProvider() {
        var provider = provider("azure-1", "azure", "https://example.openai.azure.com");
        var gateway = gateway(List.of(provider), List.of(model("azure-chat", provider.id, "my-deployment")), null);

        assertThrows(BadRequestException.class, () -> gateway.completion(request("azure-chat")));
    }

    private CompletionRequest request(String model) {
        return CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), null, null, model, null);
    }

    @SuppressWarnings("unchecked")
    private TestGatewayLLMProvider gateway(List<GatewayProviderConfig> providers, List<GatewayModelConfig> models, LiteLLMProvider fallback) {
        var routingEngine = new GatewayRoutingEngine();
        routingEngine.gatewayProviderCollection = (MongoCollection<GatewayProviderConfig>) mock(MongoCollection.class);
        routingEngine.gatewayModelCollection = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);
        when(routingEngine.gatewayProviderCollection.find(any(Query.class))).thenReturn(providers);
        when(routingEngine.gatewayModelCollection.find(any(Query.class))).thenReturn(models);
        return new TestGatewayLLMProvider(new LLMProviderConfig(null, null, null), routingEngine, new GatewaySecretProtector("test-secret"), fallback);
    }

    private GatewayProviderConfig provider(String id, String type, String baseUrl) {
        var provider = new GatewayProviderConfig();
        provider.id = id;
        provider.name = id;
        provider.type = type;
        provider.baseUrl = baseUrl;
        provider.enabled = true;
        provider.allowPrivateNetwork = true;
        provider.updatedAt = ZonedDateTime.now();
        provider.apiKeyEncrypted = new GatewaySecretProtector("test-secret").protect("sk-test");
        return provider;
    }

    private GatewayModelConfig model(String modelId, String providerId, String upstreamModel) {
        var model = new GatewayModelConfig();
        model.id = modelId;
        model.modelId = modelId;
        model.providerId = providerId;
        model.upstreamModel = upstreamModel;
        model.endpointTypes = List.of("chat.completions");
        model.enabled = true;
        model.priority = 100L;
        return model;
    }

    private static CompletionResponse response(String content) {
        return CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
                new Usage(1, 1, 2)
        );
    }

    private static final class TestGatewayLLMProvider extends GatewayLLMProvider {
        CapturingLiteLLMProvider upstream;
        String upstreamBaseUrl;

        TestGatewayLLMProvider(LLMProviderConfig config, GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector, LiteLLMProvider fallback) {
            super(config, routingEngine, secretProtector, fallback);
        }

        @Override
        LiteLLMProvider createUpstreamProvider(GatewayProviderConfig provider) {
            upstreamBaseUrl = provider.baseUrl;
            upstream = new CapturingLiteLLMProvider(new LLMProviderConfig(null, null, null), provider.baseUrl, "ok");
            return upstream;
        }
    }

    private static final class CapturingLiteLLMProvider extends LiteLLMProvider {
        final String content;
        CompletionRequest captured;

        CapturingLiteLLMProvider(LLMProviderConfig config, String url, String content) {
            super(config, url, "sk-test");
            this.content = content;
        }

        @Override
        public CompletionResponse delegateCompletionStream(CompletionRequest dto, StreamingCallback callback) {
            captured = dto;
            return response(content);
        }
    }
}
