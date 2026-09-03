package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.trace.service.OTLPIngestService;
import ai.core.server.trace.spi.LocalSpanProcessorRegistry;
import ai.core.telemetry.TelemetryConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.http.EventSource;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayProxyServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // buildAndRegisterGlobal can only run once per JVM; all span tests share this instance
    private static final TelemetryConfig TELEMETRY = TelemetryConfig.builder().enabled(true).build();

    private static byte[] json(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, String> spanAttributes(io.opentelemetry.proto.trace.v1.Span span) {
        var attrs = new HashMap<String, String>();
        for (var kv : span.getAttributesList()) {
            var value = kv.getValue();
            if (value.hasStringValue()) attrs.put(kv.getKey(), value.getStringValue());
            else if (value.hasIntValue()) attrs.put(kv.getKey(), String.valueOf(value.getIntValue()));
        }
        return attrs;
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Test
    void routesByModelPrefixAndMergesExtraBody() throws Exception {
        var service = service(provider("DeepSeek", "deepseek", "https://api.deepseek.com/v1", "deepseek/", "deepseek-chat"));

        service.proxyChatCompletions(json(Map.of(
                "model", "deepseek/deepseek-chat",
                "messages", List.of(Map.of("role", "user", "content", "hi"))
        )), "user-1", null, null);

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
        )), "user-1", null, null);

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
        )), "user-1", null, null));
    }

    @Test
    void routesByRegisteredModelBeforePrefixFallback() throws Exception {
        var provider = provider("LiteLLM", "litellm", "https://litellm.example.com", "litellm/", "deepseek/default");
        var service = service(provider, model("fast-chat", provider.id, "deepseek/deepseek-v4-flash", List.of("chat.completions")));

        service.proxyChatCompletions(json(Map.of(
                "model", "fast-chat",
                "messages", List.of(Map.of("role", "user", "content", "hi"))
        )), "user-1", null, null);

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
        )), "user-1", null, null));
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
        )), "user-1", null, null);

        assertEquals("https://litellm.example.com/responses", service.captured.uri);
        var body = MAPPER.readValue(service.captured.body, MAP_TYPE);
        assertEquals("deepseek/response-model", body.get("model"));
    }

    @Test
    void stripsReasoningEffortWhenModelDeclaresUnsupported() throws Exception {
        var provider = provider("Azure", "azure", "https://example.openai.azure.com/openai/v1", "azure/", "gpt-5.6-luna");
        var model = model("luna-chat", provider.id, "gpt-5.6-luna", List.of("chat.completions"));
        model.supportsReasoningEffort = Boolean.FALSE;
        var service = service(provider, model);

        service.proxyChatCompletions(json(Map.of(
                "model", "luna-chat",
                "messages", List.of(Map.of("role", "user", "content", "hi")),
                "reasoning_effort", "low"
        )), "user-1", null, null);

        var body = MAPPER.readValue(service.captured.body, MAP_TYPE);
        assertFalse(body.containsKey("reasoning_effort"));
        // a base url that already includes /openai/v1 still builds the deployment path
        assertEquals("https://example.openai.azure.com/openai/deployments/gpt-5.6-luna/chat/completions?api-version=2024-10-21", service.captured.uri);
    }

    @Test
    void azureResponsesUrlToleratesOpenAiV1Suffix() throws Exception {
        var provider = provider("Azure", "azure", "https://example.openai.azure.com/openai/v1", "azure/", "gpt-5.6-luna");
        provider.apiVersion = "2025-01-01-preview";
        var service = service(provider, model("luna-resp", provider.id, "gpt-5.6-luna", List.of("responses")));

        service.proxyResponses(json(Map.of("model", "luna-resp", "input", "hi")), "user-1", null, null);

        assertEquals("https://example.openai.azure.com/openai/responses?api-version=2025-01-01-preview", service.captured.uri);
    }

    @Test
    void keepsReasoningEffortWhenModelDoesNotDeclareSupport() throws Exception {
        var provider = provider("OpenAI", "openai", "https://api.openai.com/v1", "openai/", "gpt-4o");
        var service = service(provider, model("gpt-chat", provider.id, "gpt-4o", List.of("chat.completions")));

        service.proxyChatCompletions(json(Map.of(
                "model", "gpt-chat",
                "messages", List.of(Map.of("role", "user", "content", "hi")),
                "reasoning_effort", "low"
        )), "user-1", null, null);

        var body = MAPPER.readValue(service.captured.body, MAP_TYPE);
        assertEquals("low", body.get("reasoning_effort"));
    }

    @Test
    void fillsMissingToolResultsForInterruptedAssistantCalls() throws Exception {
        var provider = provider("OpenAI", "openai", "https://api.openai.com/v1", "openai/", "gpt-4o");
        var service = service(provider, model("gpt-chat", provider.id, "gpt-4o", List.of("chat.completions")));
        var messages = new ArrayList<>(List.of(
                Map.of("role", "user", "content", "run tools"),
                Map.of("role", "assistant", "content", "",
                        "tool_calls", List.of(
                                Map.of("id", "call-1", "type", "function", "function", Map.of("name", "exec_command", "arguments", "{}")),
                                Map.of("id", "call-2", "type", "function", "function", Map.of("name", "read_file", "arguments", "{}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "content", "done"),
                Map.of("role", "assistant", "content", "summary")
        ));

        service.proxyChatCompletions(json(Map.of("model", "gpt-chat", "messages", messages)), "user-1", null, null);

        var body = MAPPER.readValue(service.captured.body, MAP_TYPE);
        var forwarded = (List<?>) body.get("messages");
        assertEquals(5, forwarded.size());
        var stub = (Map<?, ?>) forwarded.get(2);
        assertEquals("tool", stub.get("role"));
        assertEquals("call-2", stub.get("tool_call_id"));
        assertEquals("[Tool call was interrupted and produced no result]", stub.get("content"));
    }

    @Test
    void parsesChatCompletionsUsage() {
        var usage = GatewaySupport.parseUsage(Map.of(
                "usage", Map.of(
                        "prompt_tokens", 12,
                        "completion_tokens", 34,
                        "prompt_tokens_details", Map.of("cached_tokens", 5)
                )));

        assertEquals(12, usage.inputTokens());
        assertEquals(34, usage.outputTokens());
        assertEquals(5, usage.cachedTokens());
    }

    @Test
    void parsesResponsesUsage() {
        var usage = GatewaySupport.parseUsage(Map.of(
                "usage", Map.of(
                        "input_tokens", 12,
                        "output_tokens", 34,
                        "input_tokens_details", Map.of("cached_tokens", 5)
                )));

        assertEquals(12, usage.inputTokens());
        assertEquals(34, usage.outputTokens());
        assertEquals(5, usage.cachedTokens());
    }

    @Test
    void returnsNullWithoutUsage() {
        assertNull(GatewaySupport.parseUsage(Map.of("id", "chatcmpl-1")));
        assertNull(GatewaySupport.parseUsage(Map.of("usage", Map.of())));
    }

    @Test
    void recordsSpanWithModelAndUserAttribution() throws Exception {
        var latch = new CountDownLatch(1);
        var exportRequest = new AtomicReference<ExportTraceServiceRequest>();
        var ingestService = mock(OTLPIngestService.class);
        doAnswer(invocation -> {
            exportRequest.set(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(ingestService).ingest(any(ExportTraceServiceRequest.class));
        LocalSpanProcessorRegistry.register(ingestService);
        var provider = provider("DeepSeek", "deepseek", "https://api.deepseek.com/v1", "deepseek/", "deepseek-chat");
        var service = new CapturingGatewayProxyService();
        service.routingEngine = routingEngine(List.of(provider), List.of());
        service.secretProtector = new GatewaySecretProtector("test-secret");
        service.telemetryConfig = TELEMETRY;
        try {
            service.proxyChatCompletions(json(Map.of(
                    "model", "deepseek/deepseek-chat",
                    "messages", List.of(Map.of("role", "user", "content", "hi"))
            )), "user-1", null, null);

            // span export runs on LocalSpanProcessor's async executor; under full-suite load the
            // single worker thread can be scheduled late, so allow a generous wait
            assertTrue(latch.await(15, TimeUnit.SECONDS), "span export timed out");
            var protoSpan = exportRequest.get().getResourceSpans(0).getScopeSpans(0).getSpans(0);
            var recorded = spanAttributes(protoSpan);
            assertEquals("gateway.chat.completions", protoSpan.getName());
            assertEquals("gateway", recorded.get("client.type"));
            assertEquals("deepseek-chat", recorded.get("gen_ai.request.model"));
            assertEquals("deepseek", recorded.get("gen_ai.system"));
            assertEquals("user-1", recorded.get("user.id"));
            assertEquals("12", recorded.get("gen_ai.usage.input_tokens"));
            assertEquals("34", recorded.get("gen_ai.usage.output_tokens"));
        } finally {
            LocalSpanProcessorRegistry.clear();
        }
    }

    @Test
    void stampsSessionIdFromClientSessionHeader() throws Exception {
        var latch = new CountDownLatch(2);
        var captured = new CopyOnWriteArrayList<ExportTraceServiceRequest>();
        var service = spanCapturingService(captured, latch);
        try {
            service.proxyChatCompletions(json(Map.of(
                    "model", "deepseek/deepseek-chat",
                    "messages", List.of(Map.of("role", "user", "content", "hi"))
            )), "user-1", "session-1", null);
            service.proxyChatCompletions(json(Map.of(
                    "model", "deepseek/deepseek-chat",
                    "messages", List.of(Map.of("role", "user", "content", "again"))
            )), "user-1", null, null);

            // span export runs on LocalSpanProcessor's async executor; under full-suite load the
            // single worker thread can be scheduled late, so allow a generous wait
            assertTrue(latch.await(15, TimeUnit.SECONDS), "spans exported: " + captured.size() + "/2");
            assertEquals(2, captured.size());
            assertEquals("session-1", spanAttributes(protoSpan(captured.get(0))).get("session.id"));
            assertNull(spanAttributes(protoSpan(captured.get(1))).get("session.id"));
        } finally {
            LocalSpanProcessorRegistry.clear();
        }
    }

    @Test
    void synthesizesAgentRootAndToolSpansFromRequestMessages() throws Exception {
        var captured = new CopyOnWriteArrayList<ExportTraceServiceRequest>();
        var latch = new CountDownLatch(3);
        var service = spanCapturingService(captured, latch);
        var messages = List.of(
                Map.of("role", "user", "content", "run tools"),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                        Map.of("id", "call-1", "type", "function", "function", Map.of("name", "search_menu", "arguments", "{\"q\":\"pasta\"}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "content", "[\"margherita\"]")
        );
        try {
            service.proxyChatCompletions(json(Map.of("model", "deepseek/deepseek-chat", "messages", messages)),
                    "user-1", "session-1", "menu-agent");

            // each span exports as its own request through LocalSpanProcessor's single-worker executor
            assertTrue(latch.await(15, TimeUnit.SECONDS), "spans exported: " + captured.size() + "/3");
            var spans = new ArrayList<io.opentelemetry.proto.trace.v1.Span>();
            for (var request : captured) {
                spans.addAll(request.getResourceSpans(0).getScopeSpans(0).getSpansList());
            }
            assertEquals(3, spans.size());
            var agentSpan = spans.stream().filter(s -> "agent".equals(spanAttributes(s).get("langfuse.observation.type"))).findFirst().orElseThrow();
            var llmSpan = spans.stream().filter(s -> "generation".equals(spanAttributes(s).get("langfuse.observation.type"))).findFirst().orElseThrow();
            var toolSpan = spans.stream().filter(s -> "tool".equals(spanAttributes(s).get("langfuse.observation.type"))).findFirst().orElseThrow();

            var agentAttrs = spanAttributes(agentSpan);
            assertEquals("menu-agent", agentAttrs.get("gen_ai.agent.name"));
            assertEquals("gateway", agentAttrs.get("client.type"));
            assertEquals("session-1", agentAttrs.get("session.id"));
            assertEquals("user-1", agentAttrs.get("user.id"));

            assertEquals(agentSpan.getSpanId(), llmSpan.getParentSpanId());
            var toolAttrs = spanAttributes(toolSpan);
            assertEquals("search_menu", toolSpan.getName());
            assertEquals("search_menu", toolAttrs.get("tool.name"));
            assertEquals("{\"q\":\"pasta\"}", toolAttrs.get("gen_ai.prompt"));
            assertEquals("[\"margherita\"]", toolAttrs.get("gen_ai.completion"));
            assertEquals("session-1", toolAttrs.get("session.id"));
            assertEquals(llmSpan.getSpanId(), toolSpan.getParentSpanId());
        } finally {
            LocalSpanProcessorRegistry.clear();
        }
    }

    @Test
    void withoutAgentNameHeaderLlmSpanStaysRoot() throws Exception {
        var captured = new CopyOnWriteArrayList<ExportTraceServiceRequest>();
        var latch = new CountDownLatch(1);
        var service = spanCapturingService(captured, latch);
        try {
            service.proxyChatCompletions(json(Map.of(
                    "model", "deepseek/deepseek-chat",
                    "messages", List.of(Map.of("role", "user", "content", "hi"))
            )), "user-1", null, null);

            assertTrue(latch.await(15, TimeUnit.SECONDS), "span export timed out");
            var spans = captured.getFirst().getResourceSpans(0).getScopeSpans(0).getSpansList();
            assertEquals(1, spans.size());
            assertEquals("gateway.chat.completions", spans.getFirst().getName());
        } finally {
            LocalSpanProcessorRegistry.clear();
        }
    }

    @Test
    void streamingResponseRecordsAssistantOutput() throws Exception {
        var latch = new CountDownLatch(1);
        var exportRequest = new AtomicReference<ExportTraceServiceRequest>();
        var ingestService = mock(OTLPIngestService.class);
        doAnswer(invocation -> {
            exportRequest.set(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(ingestService).ingest(any(ExportTraceServiceRequest.class));
        LocalSpanProcessorRegistry.register(ingestService);
        var provider = provider("DeepSeek", "deepseek", "https://api.deepseek.com/v1", "deepseek/", "deepseek-chat");
        var service = new CapturingStreamingGatewayProxyService();
        service.routingEngine = routingEngine(List.of(provider), List.of());
        service.secretProtector = new GatewaySecretProtector("test-secret");
        service.telemetryConfig = TELEMETRY;
        var source = mock(EventSource.class);
        when(source.iterator()).thenReturn(List.of(
                new EventSource.Event(null, null, "{\"choices\":[{\"delta\":{\"content\":\"hello \"}}]}"),
                new EventSource.Event(null, null, "{\"choices\":[{\"delta\":{\"content\":\"world\"}}]}"),
                new EventSource.Event(null, null, "[DONE]")
        ).iterator());
        service.source = source;
        try {
            service.proxyChatCompletions(json(Map.of(
                    "model", "deepseek/deepseek-chat",
                    "stream", Boolean.TRUE,
                    "messages", List.of(Map.of("role", "user", "content", "hi"))
            )), "user-1", null, null);

            assertTrue(latch.await(15, TimeUnit.SECONDS), "span export timed out");
            var recorded = spanAttributes(protoSpan(exportRequest.get()));
            assertEquals("hello world", recorded.get("langfuse.observation.output"));
        } finally {
            LocalSpanProcessorRegistry.clear();
        }
    }

    private CapturingGatewayProxyService spanCapturingService(List<ExportTraceServiceRequest> captured, CountDownLatch latch) {
        var ingestService = mock(OTLPIngestService.class);
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(ingestService).ingest(any(ExportTraceServiceRequest.class));
        LocalSpanProcessorRegistry.register(ingestService);
        var provider = provider("DeepSeek", "deepseek", "https://api.deepseek.com/v1", "deepseek/", "deepseek-chat");
        var service = new CapturingGatewayProxyService();
        service.routingEngine = routingEngine(List.of(provider), List.of());
        service.secretProtector = new GatewaySecretProtector("test-secret");
        service.telemetryConfig = TELEMETRY;
        return service;
    }

    private io.opentelemetry.proto.trace.v1.Span protoSpan(ExportTraceServiceRequest request) {
        return request.getResourceSpans(0).getScopeSpans(0).getSpans(0);
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
        provider.enabled = Boolean.TRUE;
        provider.allowPrivateNetwork = Boolean.TRUE;
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
        model.enabled = Boolean.TRUE;
        model.priority = priority;
        return model;
    }

    private static class CapturingGatewayProxyService extends GatewayProxyService {
        HTTPRequest captured;

        @Override
        HTTPResponse execute(HTTPRequest request, GatewayProviderConfig provider) {
            captured = request;
            return new HTTPResponse(200, Map.of("Content-Type", "application/json"),
                    "{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34}}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class CapturingStreamingGatewayProxyService extends CapturingGatewayProxyService {
        EventSource source;

        @Override
        EventSource sse(HTTPRequest request, GatewayProviderConfig provider) {
            return source;
        }
    }
}
