package ai.core.llm.providers;

import ai.core.agent.Agent;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Benchmark test to verify OpenRouter compatibility with core-ai framework
 * and compare response speed between OpenRouter and Azure.
 *
 * Config is loaded from agent.properties:
 *   openrouter.api.key / openrouter.api.base
 *   azure.benchmark.key / azure.benchmark.endpoint
 *
 * Run:
 *   ./gradlew :core-ai:test --tests "OpenRouterVsAzureBenchmarkTest"
 *
 * @author Xander
 */
@Disabled
class OpenRouterVsAzureBenchmarkTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenRouterVsAzureBenchmarkTest.class);

    private static final String OPENROUTER_MODEL = "openai/gpt-4.1-nano";
    private static final String AZURE_MODEL = "gpt-4.1-nano";
    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final int WARMUP_ROUNDS = 1;
    private static final int BENCHMARK_ROUNDS = 3;
    private static final String TEST_PROMPT = "Explain what is dependency injection in 2 sentences.";
    private static final String SYSTEM_PROMPT = "You are a concise assistant. Keep answers under 50 words.";

    private static String openRouterKey;
    private static String openRouterBase;
    private static String azureKey;
    private static String azureEndpoint;

    private static LiteLLMProvider openRouterProvider;
    private static HTTPClient httpClient;

    @BeforeAll
    static void setup() throws IOException {
        var props = new Properties();
        try (var is = OpenRouterVsAzureBenchmarkTest.class.getResourceAsStream("/agent.properties")) {
            props.load(is);
        }
        openRouterKey = props.getProperty("openrouter.api.key");
        openRouterBase = props.getProperty("openrouter.api.base");
        azureKey = props.getProperty("azure.benchmark.key");
        azureEndpoint = props.getProperty("azure.benchmark.endpoint");

        var config = new LLMProviderConfig(OPENROUTER_MODEL, DEFAULT_TEMPERATURE, null);
        config.setTimeout(120L);
        openRouterProvider = new LiteLLMProvider(config, openRouterBase, openRouterKey);

        httpClient = HTTPClient.builder()
                .timeout(Duration.ofSeconds(120))
                .connectTimeout(Duration.ofSeconds(5))
                .trustAll()
                .build();
    }

    // ===== OpenRouter compatibility tests (via LiteLLMProvider) =====

    @Test
    void testOpenRouterCompletion() {
        LOGGER.info("=== OpenRouter Compatibility Test ===");
        var request = buildProviderRequest(OPENROUTER_MODEL, TEST_PROMPT);
        var response = openRouterProvider.completion(request);
        var content = response.choices.getFirst().message.content;

        LOGGER.info("OpenRouter response: {}", content);
        LOGGER.info("OpenRouter usage: {}", response.usage);
        assertNotNull(content, "OpenRouter should return non-null response");
        assertFalse(content.isBlank(), "OpenRouter should return non-empty response");
        LOGGER.info("=== OpenRouter compatibility: PASSED ===");
    }

    @Test
    void testOpenRouterStreaming() {
        LOGGER.info("=== OpenRouter Streaming Test ===");
        var chunks = new ArrayList<String>();
        var callback = new StreamingCallback() {
            @Override
            public void onChunk(String chunk) {
                chunks.add(chunk);
            }
        };

        var request = buildProviderRequest(OPENROUTER_MODEL, TEST_PROMPT);
        var response = openRouterProvider.completionStream(request, callback);
        var content = response.choices.getFirst().message.content;

        LOGGER.info("Received {} streaming chunks", chunks.size());
        LOGGER.info("Full response: {}", content);
        assertFalse(chunks.isEmpty(), "Should receive streaming chunks from OpenRouter");
        LOGGER.info("=== OpenRouter streaming: PASSED ===");
    }

    @Test
    void testOpenRouterAgentIntegration() {
        LOGGER.info("=== OpenRouter Agent Integration Test ===");
        var agent = Agent.builder()
                .name("openrouter-test")
                .description("Test agent using OpenRouter")
                .llmProvider(openRouterProvider)
                .model(OPENROUTER_MODEL)
                .systemPrompt(SYSTEM_PROMPT)
                .temperature(DEFAULT_TEMPERATURE)
                .compression(false)
                .maxTurn(1)
                .build();

        var result = agent.run("What is the capital of France?");
        LOGGER.info("Agent response via OpenRouter: {}", result);
        assertNotNull(result, "Agent should produce non-null output via OpenRouter");
        assertFalse(result.isBlank(), "Agent should produce non-empty output via OpenRouter");
        LOGGER.info("=== OpenRouter Agent integration: PASSED ===");
    }

    // ===== Benchmark comparison (direct HTTP for fair comparison) =====

    @Test
    void testOpenRouterVsAzureLatency() {
        LOGGER.info("=== OpenRouter vs Azure Latency Benchmark (gpt-4.1-nano) ===");
        LOGGER.info("Warmup rounds: {}, Benchmark rounds: {}", WARMUP_ROUNDS, BENCHMARK_ROUNDS);
        LOGGER.info("Test prompt: \"{}\"", TEST_PROMPT);

        LOGGER.info("--- Warming up ---");
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            directCall("OpenRouter", openRouterBase + "/chat/completions",
                    "Authorization", "Bearer " + openRouterKey, OPENROUTER_MODEL);
            directCall("Azure", azureEndpoint,
                    "api-key", azureKey, AZURE_MODEL);
        }
        LOGGER.info("Warmup done");

        var openRouterResults = new ArrayList<BenchmarkResult>();
        var azureResults = new ArrayList<BenchmarkResult>();

        for (int i = 1; i <= BENCHMARK_ROUNDS; i++) {
            LOGGER.info("--- Round {} ---", i);
            openRouterResults.add(directCall("OpenRouter", openRouterBase + "/chat/completions",
                    "Authorization", "Bearer " + openRouterKey, OPENROUTER_MODEL));
            azureResults.add(directCall("Azure", azureEndpoint,
                    "api-key", azureKey, AZURE_MODEL));
        }

        printComparisonTable(openRouterResults, azureResults);
    }

    // ===== Direct HTTP call (non-streaming, fair apples-to-apples) =====

    private BenchmarkResult directCall(String providerName, String url, String authHeader, String authValue, String model) {
        var bodyMap = new HashMap<String, Object>();
        bodyMap.put("model", model);
        bodyMap.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", TEST_PROMPT)
        ));
        bodyMap.put("temperature", DEFAULT_TEMPERATURE);
        bodyMap.put("stream", false);

        var req = new HTTPRequest(HTTPMethod.POST, url);
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        req.headers.put(authHeader, authValue);
        req.body(JsonUtil.toJson(bodyMap).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        long startTime = System.nanoTime();
        var rsp = httpClient.execute(req);
        long totalMs = (System.nanoTime() - startTime) / 1_000_000;

        if (rsp.statusCode != 200) {
            LOGGER.error("{} request failed ({}): {}", providerName, rsp.statusCode, rsp.text());
            throw new RuntimeException(providerName + " request failed: " + rsp.statusCode);
        }

        var response = JsonUtil.fromJson(CompletionResponse.class, rsp.text());
        var content = response.choices.getFirst().message.content;
        int totalTokens = response.usage != null ? response.usage.getTotalTokens() : 0;
        int completionTokens = response.usage != null ? response.usage.getCompletionTokens() : 0;
        double tokensPerSec = completionTokens > 0 && totalMs > 0 ? completionTokens * 1000.0 / totalMs : 0;

        LOGGER.info("  {}: Total={}ms, Tokens={}, Speed={} tok/s, Response=\"{}\"",
                providerName, totalMs, totalTokens, String.format("%.1f", tokensPerSec),
                content != null && content.length() > 80 ? content.substring(0, 80) + "..." : content);

        return new BenchmarkResult(totalMs, totalTokens, completionTokens, tokensPerSec);
    }

    // ===== Helper for LiteLLMProvider-based tests =====

    private ai.core.llm.domain.CompletionRequest buildProviderRequest(String model, String userPrompt) {
        return ai.core.llm.domain.CompletionRequest.of(
                List.of(Message.of(RoleType.SYSTEM, SYSTEM_PROMPT), Message.of(RoleType.USER, userPrompt)),
                null, DEFAULT_TEMPERATURE, model, null
        );
    }

    // ===== Result printing =====

    private void printComparisonTable(List<BenchmarkResult> openRouterResults, List<BenchmarkResult> azureResults) {
        var orAvg = average(openRouterResults);
        var azAvg = average(azureResults);

        LOGGER.info("========== BENCHMARK COMPARISON RESULTS (gpt-4.1-nano) ==========");
        LOGGER.info("  Metric          | OpenRouter     | Azure");
        LOGGER.info("  ----------------+----------------+----------------");
        LOGGER.info("  Avg Total Time  | {} ms        | {} ms", String.format("%8d", orAvg.totalMs), String.format("%8d", azAvg.totalMs));
        LOGGER.info("  Avg Tokens/sec  | {}           | {}", String.format("%8.1f", orAvg.tokensPerSec), String.format("%8.1f", azAvg.tokensPerSec));
        LOGGER.info("  Avg Tokens      | {}           | {}", String.format("%8d", (long) orAvg.totalTokens), String.format("%8d", (long) azAvg.totalTokens));
        LOGGER.info("  =================================================================");

        long totalDiff = orAvg.totalMs - azAvg.totalMs;
        String totalWinner = totalDiff < 0 ? "OpenRouter" : "Azure";
        LOGGER.info("  Winner: {} (faster by {}ms)", totalWinner, Math.abs(totalDiff));
    }

    private BenchmarkResult average(List<BenchmarkResult> results) {
        double total = results.stream().mapToLong(r -> r.totalMs).average().orElse(0);
        double tokens = results.stream().mapToInt(r -> r.totalTokens).average().orElse(0);
        double compTokens = results.stream().mapToInt(r -> r.completionTokens).average().orElse(0);
        double tps = results.stream().mapToDouble(r -> r.tokensPerSec).average().orElse(0);
        return new BenchmarkResult((long) total, (int) tokens, (int) compTokens, tps);
    }

    record BenchmarkResult(long totalMs, int totalTokens, int completionTokens, double tokensPerSec) {
    }
}
