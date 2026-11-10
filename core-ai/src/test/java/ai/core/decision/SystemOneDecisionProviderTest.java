package ai.core.decision;

import ai.core.utils.JsonUtil;
import com.sun.net.httpserver.HttpServer;
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
class SystemOneDecisionProviderTest {
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private HttpServer server;
    private volatile String responseBody;
    private volatile int responseStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        responseBody = """
                {"model":"microsoft-decision-1","answers":{
                  "is_urgent":{"type":"noul","noul":0.0293},
                  "severity":{"type":"score","score":2.996,"legend":{"0":"Cosmetic","1":"Minor","2":"Major","3":"Critical"},
                              "probabilities":{"0":0.0002,"1":0.0003,"2":0.0028,"3":0.9967},"confidence":0.9956},
                  "team":{"type":"choice","choice":"engineering",
                          "probabilities":{"billing":0.0012,"engineering":0.9985,"support":0.0004},"confidence":0.9977}},
                 "usage":{"input_tokens":125,"output_tokens":3}}
                """;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            var apiKey = exchange.getRequestHeaders().getFirst("api-key");
            lastApiKey.set(apiKey != null ? apiKey : exchange.getRequestHeaders().getFirst("Authorization"));
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
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
    @SuppressWarnings("unchecked")
    void sendsQuestionsAndNormalizesEveryAnswerType() {
        var provider = provider();

        var response = provider.decide(new DecisionRequest("Decision-1", "ping", List.of(
                DecisionQuestion.noul("is_urgent", "Is it urgent?", null),
                DecisionQuestion.choice("team", "Which team?", Map.of("billing", "Billing", "engineering", "Engineering", "support", "Support")),
                DecisionQuestion.score("severity", "How severe?", List.of("Cosmetic", "Minor", "Major", "Critical")))));

        assertEquals("/providers/microsoft/v1/systemone", lastPath.get());
        assertEquals("test-key", lastApiKey.get());
        var body = body();
        assertEquals("Decision-1", body.get("model"));
        assertEquals("ping", body.get("state"));
        var questions = (Map<String, Object>) body.get("questions");
        assertEquals("noul", ((Map<String, Object>) questions.get("is_urgent")).get("type"));
        assertEquals(List.of("Cosmetic", "Minor", "Major", "Critical"),
                ((Map<String, Object>) questions.get("severity")).get("criteria"));
        assertTrue(((Map<String, Object>) questions.get("team")).get("criteria") instanceof Map);

        assertEquals("microsoft-decision-1", response.model());
        assertEquals(125, response.usage().inputTokens());
        assertEquals(3, response.usage().outputTokens());
        assertEquals(0.0293, response.answers().get("is_urgent").probability());
        assertEquals(DecisionType.CHOICE, response.answers().get("team").type());
        assertEquals("engineering", response.answers().get("team").choice());
        assertEquals(0.9985, response.answers().get("team").probability());
        assertEquals(2.996, response.answers().get("severity").score());
        assertEquals("Critical", response.answers().get("severity").legend().get("3"));
    }

    @Test
    void passesJsonStateAsAJsonValue() {
        var provider = provider();

        provider.decide(new DecisionRequest("Decision-1", "{\"message\":\"down\"}", List.of(
                DecisionQuestion.noul("q", "Is this a test?", null))));

        assertTrue(body().get("state") instanceof Map);
    }

    @Test
    void sendsBearerForAuthorizationProfiles() {
        var provider = new SystemOneDecisionProvider(baseUrl(), "jev-key", "Authorization", "/v1/systemone");

        provider.decide(new DecisionRequest("jev-latest", "ping", List.of(
                DecisionQuestion.noul("q", "Is this a test?", null))));

        assertEquals("/v1/systemone", lastPath.get());
        assertEquals("Bearer jev-key", lastApiKey.get());
    }

    @Test
    void keepsVendorStatusCodeAndErrorCode() {
        responseStatus = 429;
        responseBody = "{\"error\":{\"code\":\"rate_limited\",\"message\":\"slow down\"}}";
        var provider = provider();

        var error = assertThrows(DecisionException.class, () -> provider.decide(new DecisionRequest("Decision-1", "ping",
                List.of(DecisionQuestion.noul("q", "Is this a test?", null)))));

        assertEquals(429, error.statusCode());
        assertEquals("rate_limited", error.code());
        assertTrue(error.getMessage().contains("slow down"));
    }

    @Test
    void rejectsDuplicateQuestionNames() {
        var provider = provider();

        assertThrows(IllegalArgumentException.class, () -> provider.decide(new DecisionRequest("Decision-1", "ping",
                List.of(DecisionQuestion.noul("q", "One?", null), DecisionQuestion.noul("q", "Two?", null)))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body() {
        return (Map<String, Object>) JsonUtil.fromJson(Map.class, lastRequest.get());
    }

    private SystemOneDecisionProvider provider() {
        return new SystemOneDecisionProvider(baseUrl(), "test-key", "api-key", "/providers/microsoft/v1/systemone");
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostName() + ":" + server.getAddress().getPort();
    }
}
