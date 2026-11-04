package ai.core.server.gateway;

import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import com.sun.net.httpserver.HttpServer;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the failover path through the real HTTP/SSE transport instead of a stubbed upstream:
 * a provider reporting an in-stream error must hand the request to the next model in the chain.
 */
class GatewayLLMProviderFailoverIntegrationTest {
    @SuppressWarnings("unchecked")
    private static <T> MongoCollection<T> mockMongo(List<T> rows) {
        var collection = (MongoCollection<T>) mock(MongoCollection.class);
        when(collection.find(any(Query.class))).thenReturn(rows);
        when(collection.get(any(String.class))).thenReturn(Optional.empty());
        return collection;
    }

    private HttpServer failingServer;
    private HttpServer healthyServer;

    @AfterEach
    void stopServers() {
        if (failingServer != null) failingServer.stop(0);
        if (healthyServer != null) healthyServer.stop(0);
    }

    @Test
    void switchesToFallbackModelWhenUpstreamStreamReportsAnError() throws IOException {
        var failingCalls = new int[1];
        failingServer = server(exchange -> {
            failingCalls[0]++;
            respond(exchange, """
                    data: {"error":{"message":"upstream overloaded, retry later"}}

                    data: [DONE]

                    """);
        });
        var healthyCalls = new int[1];
        healthyServer = server(exchange -> {
            healthyCalls[0]++;
            respond(exchange, """
                    data: {"choices":[{"delta":{"role":"assistant"},"index":0}]}

                    data: {"choices":[{"delta":{"content":"served by fallback"},"index":0}]}

                    data: [DONE]

                    """);
        });

        var primaryProvider = provider("deepseek", "http://127.0.0.1:" + failingServer.getAddress().getPort() + "/v1");
        var backupProvider = provider("azure", "http://127.0.0.1:" + healthyServer.getAddress().getPort() + "/v1");
        var primaryModel = model("deepseek-v4", primaryProvider.id);
        primaryModel.fallbackModelIds = List.of("gpt-6");
        var gateway = gateway(List.of(primaryProvider, backupProvider), List.of(primaryModel, model("gpt-6", backupProvider.id)));

        var response = gateway.completion(request("deepseek-v4"));

        assertEquals("served by fallback", response.choices.getFirst().message.content);
        assertEquals(1, failingCalls[0]);
        assertEquals(1, healthyCalls[0]);
    }

    @Test
    void doesNotSwitchWhenUpstreamRejectsTheRequest() throws IOException {
        var failingCalls = new int[1];
        failingServer = server(exchange -> {
            failingCalls[0]++;
            respond(exchange, """
                    data: {"error":{"message":"invalid request: context length exceeded"}}

                    data: [DONE]

                    """);
        });
        var healthyCalls = new int[1];
        healthyServer = server(exchange -> {
            healthyCalls[0]++;
            respond(exchange, "data: [DONE]\n\n");
        });

        var primaryProvider = provider("deepseek", "http://127.0.0.1:" + failingServer.getAddress().getPort() + "/v1");
        var backupProvider = provider("azure", "http://127.0.0.1:" + healthyServer.getAddress().getPort() + "/v1");
        var primaryModel = model("deepseek-v4", primaryProvider.id);
        primaryModel.fallbackModelIds = List.of("gpt-6");
        var gateway = gateway(List.of(primaryProvider, backupProvider), List.of(primaryModel, model("gpt-6", backupProvider.id)));

        assertThrows(RuntimeException.class, () -> gateway.completion(request("deepseek-v4")));
        assertEquals(1, failingCalls[0]);
        assertEquals(0, healthyCalls[0]);
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler::handle);
        server.start();
        return server;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @SuppressWarnings("unchecked")
    private GatewayLLMProvider gateway(List<GatewayProviderConfig> providers, List<GatewayModelConfig> models) {
        var routingEngine = new GatewayRoutingEngine();
        routingEngine.gatewayProviderCollection = mockMongo(providers);
        routingEngine.gatewayModelCollection = mockMongo(models);
        return new GatewayLLMProvider(new LLMProviderConfig(null, null, null), routingEngine, new GatewaySecretProtector("test-secret"), null);
    }

    private GatewayProviderConfig provider(String id, String baseUrl) {
        var provider = new GatewayProviderConfig();
        provider.id = id;
        provider.name = id;
        provider.type = "litellm";
        provider.baseUrl = baseUrl;
        provider.enabled = Boolean.TRUE;
        provider.updatedAt = ZonedDateTime.now();
        provider.apiKeyEncrypted = new GatewaySecretProtector("test-secret").protect("sk-test");
        return provider;
    }

    private GatewayModelConfig model(String modelId, String providerId) {
        var model = new GatewayModelConfig();
        model.id = modelId;
        model.modelId = modelId;
        model.providerId = providerId;
        model.upstreamModel = modelId;
        model.endpointTypes = List.of("chat.completions");
        model.enabled = Boolean.TRUE;
        model.priority = 100L;
        return model;
    }

    private CompletionRequest request(String model) {
        return CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), null, null, model, null);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
