package ai.core.server.gateway;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.ModalitySupport;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.run.ResponseSchemaConverter;
import core.framework.http.HTTPClientException;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        var request = request("deep-research");

        gateway.completion(request);

        // the upstream model keeps its real name, and the resolved transport must reach the request
        // so the responses bridge (not chat completions) builds the call
        assertEquals("gpt-5.2-pro", gateway.upstream.capturedModel);
        assertEquals("responses", gateway.upstream.capturedEndpoint);
        assertNull(request.getEndpoint());
    }

    @Test
    void responsesOnlyAzureModelUsesResourceLevelUrlWhenCallerDidNotSetEndpoint() {
        var provider = provider("azure-1", "azure", "https://example.openai.azure.com/openai/v1");
        var responsesModel = model("sol", provider.id, "gpt-6.1-sol");
        responsesModel.endpointTypes = List.of("responses");
        var gateway = gateway(List.of(provider), List.of(responsesModel), null);

        gateway.completion(request("sol"));

        assertEquals("https://example.openai.azure.com/openai/responses?api-version=2024-10-21", gateway.upstreamBaseUrl);
        assertEquals("responses", gateway.upstream.capturedEndpoint);
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
    void routesToolRequestsToResponsesForReasoningModelServingBothEndpoints() {
        var provider = provider("azure-1", "azure", "https://example.openai.azure.com/openai/v1");
        var model = model("gpt-6.1-sol", provider.id, "gpt-6.1-sol");
        model.endpointTypes = List.of("responses", "chat.completions");
        model.reasoningEfforts = List.of("low", "medium", "high", "xhigh");
        var gateway = gateway(List.of(provider), List.of(model), null);
        var request = request("gpt-6.1-sol");
        request.tools = List.of(tool());
        request.reasoningEffort = ReasoningEffort.HIGH;

        gateway.completion(request);

        // the chat surface rejects function tools whenever reasoning is on (the upstream error
        // fires even without an explicit effort), so the tool-bearing turn must use responses
        assertEquals("responses", gateway.upstream.capturedEndpoint);
        assertEquals("https://example.openai.azure.com/openai/responses?api-version=2024-10-21", gateway.upstreamBaseUrl);
        assertEquals("high", gateway.upstream.capturedRequest.getReasoningEffortValue());
    }

    @Test
    void keepsChatForToolRequestsWhenModelDeclaresNoReasoningLevels() {
        var provider = provider("azure-1", "azure", "https://example.openai.azure.com/openai/v1");
        var model = model("gpt-5.2", provider.id, "gpt-5.2");
        model.endpointTypes = List.of("responses", "chat.completions");
        var gateway = gateway(List.of(provider), List.of(model), null);
        var request = request("gpt-5.2");
        request.tools = List.of(tool());

        gateway.completion(request);

        assertNull(gateway.upstream.capturedEndpoint);
        assertEquals("https://example.openai.azure.com/openai/deployments/gpt-5.2/chat/completions?api-version=2024-10-21", gateway.upstreamBaseUrl);
    }

    @Test
    void keepsChatForNoneOnlyReasoningModelWithTools() {
        var provider = provider("azure-1", "azure", "https://example.openai.azure.com/openai/v1");
        var model = model("gpt-6-luna", provider.id, "gpt-6-luna");
        model.endpointTypes = List.of("responses", "chat.completions");
        model.reasoningEfforts = List.of("none");
        var gateway = gateway(List.of(provider), List.of(model), null);
        var request = request("gpt-6-luna");
        request.tools = List.of(tool());

        gateway.completion(request);

        // a none-only declaration is expressible on the chat surface (explicit "none"), so it stays there
        assertNull(gateway.upstream.capturedEndpoint);
        assertEquals("none", gateway.upstream.capturedRequest.getReasoningEffortValue());
    }

    @Test
    void keepsChatForReasoningModelWithoutTools() {
        var provider = provider("azure-1", "azure", "https://example.openai.azure.com/openai/v1");
        var model = model("gpt-6.1-sol", provider.id, "gpt-6.1-sol");
        model.endpointTypes = List.of("responses", "chat.completions");
        model.reasoningEfforts = List.of("low", "medium", "high", "xhigh");
        var gateway = gateway(List.of(provider), List.of(model), null);

        gateway.completion(request("gpt-6.1-sol"));

        assertNull(gateway.upstream.capturedEndpoint);
        assertEquals("https://example.openai.azure.com/openai/deployments/gpt-6.1-sol/chat/completions?api-version=2024-10-21", gateway.upstreamBaseUrl);
    }

    @Test
    void keepsChatForReasoningModelThatDoesNotServeResponses() {
        var provider = provider("azure-1", "azure", "https://example.openai.azure.com/openai/v1");
        var model = model("reasoning-chat", provider.id, "reasoning-chat");
        model.endpointTypes = List.of("chat.completions");
        model.reasoningEfforts = List.of("low", "high");
        var gateway = gateway(List.of(provider), List.of(model), null);
        var request = request("reasoning-chat");
        request.tools = List.of(tool());

        gateway.completion(request);

        assertNull(gateway.upstream.capturedEndpoint);
        assertEquals("https://example.openai.azure.com/openai/deployments/reasoning-chat/chat/completions?api-version=2024-10-21", gateway.upstreamBaseUrl);
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
    void vertexGeminiChatPrefixesModelWithGoogleAndRefreshesToken() {
        var provider = provider("google", "gemini", "https://aiplatform.googleapis.com/v1beta1");
        provider.vertexProjectId = "my-project";
        provider.vertexLocation = "us-central1";
        var gateway = gateway(List.of(provider), List.of(model("gemini-chat", provider.id, "gemini-3.8-flash")), null);

        gateway.completion(request("gemini-chat"));

        assertEquals("google/gemini-3.8-flash", gateway.upstream.capturedModel);
        assertEquals("https://aiplatform.googleapis.com/v1beta1/projects/my-project/locations/us-central1/endpoints/openapi",
                gateway.upstream.capturedUpdatedUrl);
        assertEquals("vertex-token", gateway.upstream.capturedUpdatedToken);
    }

    @Test
    void developerGeminiChatKeepsModelName() {
        var provider = provider("google-dev", "gemini", "https://generativelanguage.googleapis.com/v1beta");
        var gateway = gateway(List.of(provider), List.of(model("gemini-chat", provider.id, "gemini-3.8-flash")), null);

        gateway.completion(request("gemini-chat"));

        assertEquals("gemini-3.8-flash", gateway.upstream.capturedModel);
    }

    @Test
    void geminiResponsesEndpointIsRejected() {
        var provider = provider("google", "gemini", "https://aiplatform.googleapis.com/v1beta1");
        var routingEngine = new GatewayRoutingEngine();
        routingEngine.providerStore = GatewayStoreTestSupport.providerStore(List.of(provider));
        routingEngine.modelStore = GatewayStoreTestSupport.modelStore(List.of());
        var realProvider = new GatewayLLMProvider(new LLMProviderConfig(null, null, null), routingEngine, new GatewaySecretProtector("test-secret"), null);

        assertThrows(BadRequestException.class, () -> realProvider.createUpstreamProvider(provider, "gemini-3.8-flash", "responses"));
    }

    @Test
    void noneOnlyModelSendsExplicitNoneWhenRequestEffortIsAbsent() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var model = model("gpt-6-luna", provider.id, "gpt-6-luna");
        model.reasoningEfforts = List.of("none");
        var gateway = gateway(List.of(provider), List.of(model), null);

        // an agent configured with thinking effort "none" arrives here as a null request effort;
        // the none-only declaration must still put a literal "none" on the upstream request
        gateway.completion(request("gpt-6-luna"));

        assertEquals("none", gateway.upstream.capturedRequest.getReasoningEffortValue());
        assertNull(gateway.upstream.capturedRequest.reasoningEffort);
    }

    @Test
    void noneOnlyModelSendsExplicitNoneWhenRequestEffortIsNone() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var model = model("gpt-6-luna", provider.id, "gpt-6-luna");
        model.reasoningEfforts = List.of("none");
        var gateway = gateway(List.of(provider), List.of(model), null);
        var request = request("gpt-6-luna");
        request.reasoningEffort = ReasoningEffort.NONE;

        gateway.completion(request);

        assertEquals("none", gateway.upstream.capturedRequest.getReasoningEffortValue());
    }

    @Test
    void noneOnlyModelMapsAHigherRequestedEffortToNone() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var model = model("gpt-6-luna", provider.id, "gpt-6-luna");
        model.reasoningEfforts = List.of("none");
        var gateway = gateway(List.of(provider), List.of(model), null);
        var request = request("gpt-6-luna");
        request.reasoningEffort = ReasoningEffort.LOW;

        gateway.completion(request);

        assertEquals("none", gateway.upstream.capturedRequest.getReasoningEffortValue());
    }

    @Test
    void mapsRequestedReasoningEffortToSupportedLevelsIgnoringNone() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var model = model("reasoning-model", provider.id, "reasoning-model");
        model.reasoningEfforts = List.of("none", "low", "high");
        var gateway = gateway(List.of(provider), List.of(model), null);
        var request = request("reasoning-model");
        request.reasoningEffort = ReasoningEffort.LOW;

        gateway.completion(request);

        assertEquals("low", gateway.upstream.capturedRequest.getReasoningEffortValue());
    }

    @Test
    void forcesModelTemperatureOntoTheRequest() {
        var provider = provider("azure-1", "azure", "https://example.openai.azure.com/openai/v1");
        var model = model("gpt-6.1-sol", provider.id, "gpt-6.1-sol");
        model.temperature = 1D;
        var gateway = gateway(List.of(provider), List.of(model), null);
        var request = request("gpt-6.1-sol");
        request.temperature = 0.7;

        gateway.completion(request);

        assertEquals(1D, gateway.upstream.capturedRequest.temperature);
    }

    @Test
    void modelWithoutTemperatureLeavesRequestTemperatureAlone() {
        var provider = provider("litellm-1", "litellm", "https://litellm.example.com");
        var gateway = gateway(List.of(provider), List.of(model("fast-chat", provider.id, "deepseek/deepseek-v4-flash")), null);
        var request = request("fast-chat");
        request.temperature = 0.7;

        gateway.completion(request);

        assertEquals(0.7, gateway.upstream.capturedRequest.temperature);
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

    @Test
    void failsOverToFallbackModelWhenPrimaryFailsBeforeOutput() {
        var provider = provider("deepseek", "litellm", "https://deepseek.example.com");
        var primary = model("deepseek-v4", provider.id, "deepseek-v4");
        primary.isDefault = Boolean.TRUE;
        primary.fallbackModelIds = List.of("gpt-6");
        var backup = model("gpt-6", provider.id, "gpt-6");
        var gateway = scriptedGateway(List.of(provider), List.of(primary, backup));
        var upstream = gateway.upstream(provider.id);
        upstream.fail("deepseek-v4", unavailable());
        upstream.reply("gpt-6", "served by fallback");
        var request = request("deepseek-v4");

        var response = gateway.completion(request);

        assertEquals("served by fallback", response.choices.getFirst().message.content);
        assertEquals(List.of("deepseek-v4", "gpt-6"), upstream.calledModels);
        assertEquals("deepseek-v4", request.model);
    }

    @Test
    void followsConfiguredFallbackOrder() {
        var provider = provider("deepseek", "litellm", "https://deepseek.example.com");
        var primary = model("deepseek-v4", provider.id, "deepseek-v4");
        primary.fallbackModelIds = List.of("gpt-6", "gemini-3");
        var gateway = scriptedGateway(List.of(provider),
                List.of(primary, model("gpt-6", provider.id, "gpt-6"), model("gemini-3", provider.id, "gemini-3")));
        var upstream = gateway.upstream(provider.id);
        upstream.fail("deepseek-v4", unavailable());
        upstream.fail("gpt-6", unavailable());
        upstream.reply("gemini-3", "third model answered");

        var response = gateway.completion(request("deepseek-v4"));

        assertEquals("third model answered", response.choices.getFirst().message.content);
        assertEquals(List.of("deepseek-v4", "gpt-6", "gemini-3"), upstream.calledModels);
    }

    @Test
    void doesNotFailOverAfterChunksReachedTheCaller() {
        var provider = provider("deepseek", "litellm", "https://deepseek.example.com");
        var primary = model("deepseek-v4", provider.id, "deepseek-v4");
        primary.fallbackModelIds = List.of("gpt-6");
        var gateway = scriptedGateway(List.of(provider), List.of(primary, model("gpt-6", provider.id, "gpt-6")));
        var upstream = gateway.upstream(provider.id);
        upstream.emitThenFail("deepseek-v4", "partial answer", new HTTPClientException(
                "failed to read sse response, error=unexpected end of stream", "HTTP_REQUEST_FAILED"));

        assertThrows(RuntimeException.class, () -> gateway.completion(request("deepseek-v4")));
        assertEquals(List.of("deepseek-v4"), upstream.calledModels);
    }

    @Test
    void doesNotFailOverOnRequestLevelErrors() {
        var provider = provider("deepseek", "litellm", "https://deepseek.example.com");
        var primary = model("deepseek-v4", provider.id, "deepseek-v4");
        primary.fallbackModelIds = List.of("gpt-6");
        var gateway = scriptedGateway(List.of(provider), List.of(primary, model("gpt-6", provider.id, "gpt-6")));
        var upstream = gateway.upstream(provider.id);
        upstream.fail("deepseek-v4", new HTTPClientException(
                "invalid sse response, statusCode=400, content-type=application/json", "HTTP_REQUEST_FAILED"));

        assertThrows(RuntimeException.class, () -> gateway.completion(request("deepseek-v4")));
        // a 400 is request-specific: no failover, and no cooldown either — the next call still tries the primary
        assertThrows(RuntimeException.class, () -> gateway.completion(request("deepseek-v4")));
        assertEquals(List.of("deepseek-v4", "deepseek-v4"), upstream.calledModels);
    }

    @Test
    void skipsDisabledFallbackModel() {
        var provider = provider("deepseek", "litellm", "https://deepseek.example.com");
        var primary = model("deepseek-v4", provider.id, "deepseek-v4");
        primary.fallbackModelIds = List.of("gpt-6");
        var disabled = model("gpt-6", provider.id, "gpt-6");
        disabled.enabled = Boolean.FALSE;
        var gateway = scriptedGateway(List.of(provider), List.of(primary, disabled));
        var upstream = gateway.upstream(provider.id);
        upstream.fail("deepseek-v4", unavailable());

        assertThrows(RuntimeException.class, () -> gateway.completion(request("deepseek-v4")));
        assertEquals(List.of("deepseek-v4"), upstream.calledModels);
    }

    @Test
    void skipsFallbackThatDeclaresNoVisionForImageRequest() {
        var provider = provider("deepseek", "litellm", "https://deepseek.example.com");
        var primary = model("deepseek-v4", provider.id, "deepseek-v4");
        primary.fallbackModelIds = List.of("text-only");
        var textOnly = model("text-only", provider.id, "text-only");
        textOnly.supportsVision = Boolean.FALSE;
        var gateway = scriptedGateway(List.of(provider), List.of(primary, textOnly));
        var upstream = gateway.upstream(provider.id);
        upstream.fail("deepseek-v4", unavailable());
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER,
                Content.of(Content.ImageUrl.of("https://example.com/a.png", "png")))), null, null, "deepseek-v4", null);

        assertThrows(RuntimeException.class, () -> gateway.completion(request));
        assertEquals(List.of("deepseek-v4"), upstream.calledModels);
    }

    @Test
    void keepsFailedModelOutOfTheWayDuringCooldown() {
        var provider = provider("deepseek", "litellm", "https://deepseek.example.com");
        var primary = model("deepseek-v4", provider.id, "deepseek-v4");
        primary.fallbackModelIds = List.of("gpt-6");
        var gateway = scriptedGateway(List.of(provider), List.of(primary, model("gpt-6", provider.id, "gpt-6")));
        var upstream = gateway.upstream(provider.id);
        upstream.fail("deepseek-v4", unavailable());
        upstream.reply("gpt-6", "fallback answer");

        gateway.completion(request("deepseek-v4"));
        gateway.completion(request("deepseek-v4"));

        // the second call skips the unhealthy primary entirely instead of paying its timeout again
        assertEquals(List.of("deepseek-v4", "gpt-6", "gpt-6"), upstream.calledModels);
    }

    @Test
    void failsOverWhenPrimaryProviderReportsInsufficientBalance() {
        var provider = provider("deepseek", "litellm", "https://deepseek.example.com");
        var primary = model("deepseek-flash", provider.id, "deepseek-flash");
        primary.fallbackModelIds = List.of("gpt-6-luna");
        var gateway = scriptedGateway(List.of(provider),
                List.of(primary, model("gpt-6-luna", provider.id, "gpt-6-luna")));
        var upstream = gateway.upstream(provider.id);
        upstream.fail("deepseek-flash", new HTTPClientException(
                "invalid sse response, statusCode=402, content-type=application/json, "
                        + "body={\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"insufficient_balance_error\"}}",
                "HTTP_REQUEST_FAILED"));
        upstream.reply("gpt-6-luna", "served by fallback");

        var response = gateway.completion(request("deepseek-flash"));

        assertEquals("served by fallback", response.choices.getFirst().message.content);
        assertEquals(List.of("deepseek-flash", "gpt-6-luna"), upstream.calledModels);
    }

    private HTTPClientException unavailable() {
        return new HTTPClientException("invalid sse response, statusCode=503, content-type=text/html", "HTTP_REQUEST_FAILED");
    }

    private CompletionRequest request(String model) {
        return CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), null, null, model, null);
    }

    private Tool tool() {
        var tool = new Tool();
        tool.type = ToolType.FUNCTION;
        tool.function = new Function();
        tool.function.name = "lookup";
        tool.function.description = "Look up a record.";
        tool.function.parameters = new JsonSchema();
        tool.function.parameters.type = JsonSchema.PropertyType.OBJECT;
        tool.function.parameters.properties = new LinkedHashMap<>();
        return tool;
    }

    @SuppressWarnings("unchecked")
    private ScriptedGateway scriptedGateway(List<GatewayProviderConfig> providers, List<GatewayModelConfig> models) {
        var routingEngine = new GatewayRoutingEngine();
        routingEngine.providerStore = GatewayStoreTestSupport.providerStore(providers);
        routingEngine.modelStore = GatewayStoreTestSupport.modelStore(models);
        var gateway = new ScriptedGateway(new LLMProviderConfig(null, null, null), routingEngine, new GatewaySecretProtector("test-secret"));
        // the seed modality registry knows deepseek as text-only; failover tests must keep image content intact
        gateway.setModalityRegistry((model, modality) -> ModalitySupport.SUPPORTED);
        return gateway;
    }

    @SuppressWarnings("unchecked")
    private TestGatewayLLMProvider gateway(List<GatewayProviderConfig> providers, List<GatewayModelConfig> models, LiteLLMProvider fallback) {
        var routingEngine = new GatewayRoutingEngine();
        routingEngine.providerStore = GatewayStoreTestSupport.providerStore(providers);
        routingEngine.modelStore = GatewayStoreTestSupport.modelStore(models);
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

        @Override
        ai.core.media.GoogleAccessTokenProvider vertexTokenProvider(GatewayProviderConfig provider) {
            var tokenProvider = mock(ai.core.media.GoogleAccessTokenProvider.class);
            when(tokenProvider.accessToken()).thenReturn("vertex-token");
            return tokenProvider;
        }
    }

    private static final class CapturingLiteLLMProvider extends LiteLLMProvider {
        final String content;
        String capturedModel;
        String capturedEndpoint;
        CompletionRequest capturedRequest;
        String capturedUpdatedUrl;
        String capturedUpdatedToken;

        CapturingLiteLLMProvider(LLMProviderConfig config, String url, String content) {
            super(config, url, "sk-test");
            this.content = content;
        }

        @Override
        public void updateCredentials(String url, String token) {
            capturedUpdatedUrl = url;
            capturedUpdatedToken = token;
        }

        @Override
        public CompletionResponse delegateCompletionStream(CompletionRequest dto, StreamingCallback callback) {
            capturedModel = dto.model;
            capturedEndpoint = dto.getEndpoint();
            capturedRequest = dto;
            return response(content);
        }
    }

    private static final class ScriptedGateway extends GatewayLLMProvider {
        final Map<String, ScriptedUpstream> upstreams = new LinkedHashMap<>();

        ScriptedGateway(LLMProviderConfig config, GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector) {
            super(config, routingEngine, secretProtector, null);
        }

        ScriptedUpstream upstream(String providerId) {
            return upstreams.computeIfAbsent(providerId, id -> new ScriptedUpstream());
        }

        @Override
        LiteLLMProvider createUpstreamProvider(GatewayProviderConfig provider, String upstreamModel, String endpoint) {
            return upstream(provider.id);
        }
    }

    private static final class ScriptedUpstream extends LiteLLMProvider {
        final Map<String, Script> scripts = new LinkedHashMap<>();
        final List<String> calledModels = new ArrayList<>();

        ScriptedUpstream() {
            super(new LLMProviderConfig(null, null, null), "https://scripted.example.com", "sk-test");
        }

        void reply(String model, String content) {
            scripts.put(model, new Script(content, List.of(), null));
        }

        void fail(String model, RuntimeException error) {
            scripts.put(model, new Script(null, List.of(), error));
        }

        void emitThenFail(String model, String chunk, RuntimeException error) {
            scripts.put(model, new Script(null, List.of(chunk), error));
        }

        @Override
        public CompletionResponse delegateCompletionStream(CompletionRequest dto, StreamingCallback callback) {
            calledModels.add(dto.model);
            var script = scripts.get(dto.model);
            if (script == null) throw new AssertionError("unexpected upstream model: " + dto.model);
            for (var chunk : script.chunks()) {
                callback.onChunk(chunk);
            }
            if (script.error() != null) throw script.error();
            return response(script.content());
        }

        private record Script(String content, List<String> chunks, RuntimeException error) {
        }
    }
}
