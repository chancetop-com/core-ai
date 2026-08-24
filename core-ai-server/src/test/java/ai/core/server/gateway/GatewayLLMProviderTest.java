package ai.core.server.gateway;

import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.run.ResponseSchemaConverter;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayLLMProviderTest {
    private static final String SCHEMA_JSON = """
            {"type":"object","title":"DataRecord","properties":{"data":{"type":"string"}}}""";

    private static CompletionResponse response(String content) {
        return CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
                new Usage(1, 1, 2)
        );
    }

    @Test
    void routesGatewayModelIdToUpstreamModelAndRestoresRequestedModel() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var gateway = gateway(List.of(provider), List.of(model("fast-chat", provider.id, "deepseek/deepseek-v4-flash")), null);
        var request = request("fast-chat");

        var response = gateway.completion(request);

        assertEquals("ok", response.choices.getFirst().message.content);
        assertEquals("deepseek/deepseek-v4-flash", gateway.upstream.capturedModel);
        assertEquals("https://litellm.example.com", gateway.upstreamBaseUrl);
        assertEquals("fast-chat", request.model);
    }

    @Test
    void routesResponsesOnlyModelThroughResponsesTransport() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var responsesModel = model("deep-research", provider.id, "gpt-5.2-pro");
        responsesModel.endpointTypes = List.of("responses");
        var gateway = gateway(List.of(provider), List.of(responsesModel), null);

        gateway.completion(request("deep-research"));

        // transport is request-endpoint driven: the upstream model keeps its real name
        assertEquals("gpt-5.2-pro", gateway.upstream.capturedModel);
    }

    @Test
    void routesExplicitResponsesRequestEvenWhenModelAlsoSupportsChat() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var model = model("gpt-mini", provider.id, "gpt-5-mini");
        model.endpointTypes = List.of("chat.completions", "responses");
        var gateway = gateway(List.of(provider), List.of(model), null);

        var request = request("gpt-mini");
        request.setEndpoint("responses");
        gateway.completion(request);

        assertEquals("gpt-5-mini", gateway.upstream.capturedModel);
    }

    @Test
    void throwsWhenResponsesRequestRoutesToModelWithoutResponsesEndpoint() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var model = model("chat-only", provider.id, "deepseek/deepseek-chat");
        var gateway = gateway(List.of(provider), List.of(model), null);

        var request = request("chat-only");
        request.setEndpoint("responses");

        assertThrows(BadRequestException.class, () -> gateway.completion(request));
    }

    @Test
    void derivesResponsesEndpointFromFileContent() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var model = model("gpt-mini", provider.id, "gpt-5-mini");
        model.endpointTypes = List.of("chat.completions", "responses");
        var gateway = gateway(List.of(provider), List.of(model), null);

        var request = CompletionRequest.of(List.of(Message.of(new Message.MessageRecord(RoleType.USER,
                        List.of(Content.of("read this"), Content.ofFileUrl("https://example.com/a.pdf")),
                        null, null, null, null))),
                null, null, "gpt-mini", null);
        gateway.completion(request);

        assertEquals("gpt-5-mini", gateway.upstream.capturedModel);
    }

    @Test
    void fallsBackToStaticProviderWhenNoGatewayProviders() {
        var fallback = new CapturingLiteLLMProvider(new LLMProviderConfig(null, null, null), "https://static.example.com", "answered by fallback");
        var gateway = gateway(List.of(), List.of(), fallback);

        var response = gateway.completion(request("gpt-4o"));

        assertEquals("answered by fallback", response.choices.getFirst().message.content);
        assertEquals("gpt-4o", fallback.capturedModel);
    }

    @Test
    void fallsBackToStaticProviderWhenModelNotRegistered() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var fallback = new CapturingLiteLLMProvider(new LLMProviderConfig(null, null, null), "https://static.example.com", "answered by fallback");
        var gateway = gateway(List.of(provider), List.of(model("fast-chat", provider.id, "deepseek/deepseek-v4-flash")), fallback);

        var response = gateway.completion(request("legacy-model"));

        assertEquals("answered by fallback", response.choices.getFirst().message.content);
        assertEquals("legacy-model", fallback.capturedModel);
    }

    @Test
    void disabledModelStaysBlockedInsteadOfFallingBack() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var disabled = model("fast-chat", provider.id, "deepseek/deepseek-v4-flash");
        disabled.enabled = Boolean.FALSE;
        var fallback = new CapturingLiteLLMProvider(new LLMProviderConfig(null, null, null), "https://static.example.com", "answered by fallback");
        var gateway = gateway(List.of(provider), List.of(disabled), fallback);

        assertThrows(BadRequestException.class, () -> gateway.completion(request("fast-chat")));
    }

    @Test
    void throwsWhenNoRouteAndNoFallback() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var gateway = gateway(List.of(provider), List.of(model("fast-chat", provider.id, "deepseek/deepseek-v4-flash")), null);

        assertThrows(BadRequestException.class, () -> gateway.completion(request("unknown-model")));
    }

    @Test
    void azureRouteGoesThroughUpstreamProvider() {
        var provider = provider("azure-1", "azure", "https://example.openai.azure.com/openai/v1");
        var gateway = gateway(List.of(provider), List.of(model("azure-chat", provider.id, "my-deployment")), null);

        var response = gateway.completion(request("azure-chat"));

        assertEquals("ok", response.choices.getFirst().message.content);
        assertEquals("my-deployment", gateway.upstream.capturedModel);
        assertEquals("https://example.openai.azure.com/openai/deployments/my-deployment/chat/completions?api-version=2024-10-21", gateway.upstreamBaseUrl);
    }

    @Test
    void azureResponsesRouteUsesResourceLevelUrlWithRealModelName() {
        var provider = provider("azure-1", "azure", "https://example.openai.azure.com/openai/v1");
        var responsesModel = model("azure-resp", provider.id, "gpt-5-mini");
        responsesModel.endpointTypes = List.of("responses");
        var gateway = gateway(List.of(provider), List.of(responsesModel), null);

        var request = request("azure-resp");
        request.setEndpoint("responses");
        gateway.completion(request);

        assertEquals("https://example.openai.azure.com/openai/responses?api-version=2024-10-21", gateway.upstreamBaseUrl);
        assertEquals("gpt-5-mini", gateway.upstream.capturedModel);
    }

    @Test
    void downgradesJsonSchemaToJsonObjectForJsonModeOnlyModel() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var deepseek = model("deepseek-chat", provider.id, "deepseek/deepseek-chat");
        deepseek.responseFormat = "json_object";
        var gateway = gateway(List.of(provider), List.of(deepseek), null);

        var request = request("deepseek-chat");
        request.responseFormat = ResponseSchemaConverter.fromJsonSchema(SCHEMA_JSON);
        gateway.completion(request);

        assertEquals("json_object", gateway.upstream.capturedRequest.responseFormat.type);
        assertNull(gateway.upstream.capturedRequest.responseFormat.jsonSchema);
        var systemContent = gateway.upstream.capturedRequest.messages.stream()
                .filter(message -> message.role == RoleType.SYSTEM)
                .findFirst().orElseThrow().content;
        assertTrue(systemContent.stream().anyMatch(content -> content.text != null && content.text.contains("\"title\":\"DataRecord\"")));
    }

    @Test
    void keepsJsonSchemaForDefaultModelConfig() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var gateway = gateway(List.of(provider), List.of(model("fast-chat", provider.id, "gpt-4o")), null);

        var request = request("fast-chat");
        request.responseFormat = ResponseSchemaConverter.fromJsonSchema(SCHEMA_JSON);
        gateway.completion(request);

        assertEquals("json_schema", gateway.upstream.capturedRequest.responseFormat.type);
        assertEquals("DataRecord", gateway.upstream.capturedRequest.responseFormat.jsonSchema.name);
        assertEquals(1, gateway.upstream.capturedRequest.messages.size());
    }

    @Test
    void doesNotTouchRequestWithoutResponseFormat() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var deepseek = model("deepseek-chat", provider.id, "deepseek/deepseek-chat");
        deepseek.responseFormat = "json_object";
        var gateway = gateway(List.of(provider), List.of(deepseek), null);

        gateway.completion(request("deepseek-chat"));

        assertNull(gateway.upstream.capturedRequest.responseFormat);
        assertEquals(1, gateway.upstream.capturedRequest.messages.size());
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
        provider.enabled = Boolean.TRUE;
        provider.allowPrivateNetwork = Boolean.TRUE;
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
        model.enabled = Boolean.TRUE;
        model.priority = 100L;
        return model;
    }

    private static final class TestGatewayLLMProvider extends GatewayLLMProvider {
        CapturingLiteLLMProvider upstream;
        String upstreamBaseUrl;

        TestGatewayLLMProvider(LLMProviderConfig config, GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector, LiteLLMProvider fallback) {
            super(config, routingEngine, secretProtector, fallback);
        }

        @Override
        LiteLLMProvider createUpstreamProvider(GatewayProviderConfig provider, String upstreamModel, String endpoint) {
            upstreamBaseUrl = "azure".equals(provider.type)
                    ? GatewayModelService.ENDPOINT_RESPONSES.equals(endpoint)
                        ? "https://example.openai.azure.com/openai/responses?api-version=2024-10-21"
                        : "https://example.openai.azure.com/openai/deployments/" + upstreamModel + "/chat/completions?api-version=2024-10-21"
                    : provider.baseUrl;
            upstream = new CapturingLiteLLMProvider(new LLMProviderConfig(null, null, null), upstreamBaseUrl, "ok");
            return upstream;
        }
    }

    private static final class CapturingLiteLLMProvider extends LiteLLMProvider {
        final String content;
        String capturedModel;
        CompletionRequest capturedRequest;

        CapturingLiteLLMProvider(LLMProviderConfig config, String url, String content) {
            super(config, url, "sk-test");
            this.content = content;
        }

        @Override
        public CompletionResponse delegateCompletionStream(CompletionRequest dto, StreamingCallback callback) {
            capturedModel = dto.model;
            capturedRequest = dto;
            return response(content);
        }
    }
}
