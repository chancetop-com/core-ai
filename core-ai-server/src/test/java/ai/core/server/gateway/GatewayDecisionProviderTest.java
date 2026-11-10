package ai.core.server.gateway;

import ai.core.decision.DecisionQuestion;
import ai.core.decision.DecisionRequest;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.utils.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class GatewayDecisionProviderTest {
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastApiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = """
                    {"model":"microsoft-decision-1","answers":{"q":{"type":"noul","noul":0.25}},
                     "usage":{"input_tokens":5,"output_tokens":1}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void routesTheRegisteredModelAndRewritesItToTheUpstreamDeployment() {
        var gateway = gateway(provider(), model("decision-1", "Decision-1"));

        var response = gateway.decide(new DecisionRequest("decision-1", "ping",
                List.of(DecisionQuestion.noul("q", "Is this a test?", null))));

        assertEquals("/providers/microsoft/v1/systemone", lastPath.get());
        assertEquals("test-key", lastApiKey.get());
        assertEquals("Decision-1", body().get("model"));
        assertEquals(0.25, response.answers().get("q").probability());
        assertEquals("microsoft-decision-1", response.model());
    }

    @Test
    void fallsBackToTheOnlyDecisionModelWhenTheRequestOmitsIt() {
        var gateway = gateway(provider(), model("decision-1", "Decision-1"));

        gateway.decide(new DecisionRequest(null, "ping", List.of(DecisionQuestion.noul("q", "?", null))));

        assertEquals("Decision-1", body().get("model"));
    }

    @Test
    void rejectsAModelRegisteredForOtherEndpoints() {
        var chat = model("chat-model", "chat-model");
        chat.endpointTypes = List.of("chat.completions");
        var gateway = gateway(provider(), chat);

        var error = assertThrows(BadRequestException.class, () -> gateway.decide(new DecisionRequest("chat-model", "ping",
                List.of(DecisionQuestion.noul("q", "?", null)))));

        assertTrue(error.getMessage().contains("does not support endpoint"));
        assertTrue(error.getMessage().contains("decision"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body() {
        return (Map<String, Object>) JsonUtil.fromJson(Map.class, lastRequest.get());
    }

    private GatewayDecisionProvider gateway(GatewayProviderConfig provider, GatewayModelConfig model) {
        var engine = new GatewayRoutingEngine();
        engine.providerStore = GatewayStoreTestSupport.providerStore(List.of(provider));
        engine.modelStore = GatewayStoreTestSupport.modelStore(List.of(model));
        return new GatewayDecisionProvider(engine, new GatewaySecretProtector("test-secret"));
    }

    private GatewayProviderConfig provider() {
        var provider = new GatewayProviderConfig();
        provider.id = "provider-1";
        provider.name = "foundry";
        provider.type = "azure";
        provider.baseUrl = baseUrl();
        provider.apiKey = "test-key";
        provider.decisionProtocol = "AZURE_FOUNDRY_DECISION";
        provider.allowPrivateNetwork = Boolean.TRUE;
        provider.enabled = Boolean.TRUE;
        return provider;
    }

    private GatewayModelConfig model(String modelId, String upstreamModel) {
        var model = new GatewayModelConfig();
        model.id = "model-" + modelId;
        model.modelId = modelId;
        model.providerId = "provider-1";
        model.upstreamModel = upstreamModel;
        model.endpointTypes = List.of("decision");
        model.enabled = Boolean.TRUE;
        model.priority = 100L;
        return model;
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostName() + ":" + server.getAddress().getPort();
    }
}
