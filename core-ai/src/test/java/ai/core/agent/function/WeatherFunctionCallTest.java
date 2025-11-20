package ai.core.agent.function;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.tool.function.Functions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for function calling with WeatherService using mock LLM provider
 * This test doesn't require a real LLM service
 *
 * @author stephen
 */
class WeatherFunctionCallTest {
    private final Logger logger = LoggerFactory.getLogger(WeatherFunctionCallTest.class);
    private MockLLMProvider mockLLMProvider;
    private WeatherService weatherService;

    @BeforeEach
    void setUp() {
        // Create mock LLM provider
        mockLLMProvider = new MockLLMProvider();

        // Create weather service
        weatherService = new WeatherService();
    }

    @Test
    void testWeatherFunctionCall() {
        // First call: LLM decides to call getTemperature function
        FunctionCall toolCall = FunctionCall.of(
            "call_123",
            "function",
            "getTemperature",
            "{\"city\":\"shanghai\"}"
        );

        Message toolCallMessage = Message.of(
            RoleType.ASSISTANT,
            "",
            null,
            null,
            null,
            List.of(toolCall)
        );

        CompletionResponse toolCallResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.TOOL_CALLS, toolCallMessage)),
            new Usage(100, 20, 120)
        );

        // Second call: LLM returns final answer after function execution
        Message finalMessage = Message.of(
            RoleType.ASSISTANT,
            "The temperature in Shanghai is 30.0°C."
        );

        CompletionResponse finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(150, 30, 180)
        );

        // Add mock responses
        mockLLMProvider.addResponse(toolCallResponse);
        mockLLMProvider.addResponse(finalResponse);

        // Create agent with mock provider
        var agent = Agent.builder()
                .name("weather-agent")
                .description("get weather of a city")
                .systemPrompt("You are an assistant that helps users get weather information.")
                .toolCalls(Functions.from(weatherService, "getTemperature", "getAirQuality"))
                .llmProvider(mockLLMProvider)
                .build();

        // Test temperature query
        String query = "What is the temperature in shanghai?";
        logger.info("Testing query: {}", query);
        String result = agent.run(query, ExecutionContext.empty());
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("30") || result.contains("temperature"),
                   "Result should contain temperature information");
    }

    @Test
    void testAirQualityFunctionCall() {
        // First call: LLM decides to call getAirQuality function
        FunctionCall toolCall = FunctionCall.of(
            "call_456",
            "function",
            "getAirQuality",
            "{\"city\":\"beijing\"}"
        );

        Message toolCallMessage = Message.of(
            RoleType.ASSISTANT,
            "",
            null,
            null,
            null,
            List.of(toolCall)
        );

        CompletionResponse toolCallResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.TOOL_CALLS, toolCallMessage)),
            new Usage(100, 20, 120)
        );

        // Second call: LLM returns final answer
        Message finalMessage = Message.of(
            RoleType.ASSISTANT,
            "The air quality in Beijing is bad."
        );

        CompletionResponse finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(150, 30, 180)
        );

        mockLLMProvider.addResponse(toolCallResponse);
        mockLLMProvider.addResponse(finalResponse);

        var agent = Agent.builder()
                .name("weather-agent")
                .description("get weather of a city")
                .systemPrompt("You are an assistant that helps users get weather information.")
                .toolCalls(Functions.from(weatherService, "getTemperature", "getAirQuality"))
                .llmProvider(mockLLMProvider)
                .build();

        String query = "How is the air quality in beijing?";
        logger.info("Testing query: {}", query);
        String result = agent.run(query, ExecutionContext.empty());
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.toLowerCase(Locale.ENGLISH).contains("bad") || result.toLowerCase(Locale.ENGLISH).contains("air quality"),
                   "Result should contain air quality information");
    }

    @Test
    void testMultipleFunctionCalls() {
        // First call: LLM decides to call getTemperature twice
        FunctionCall toolCall1 = FunctionCall.of(
            "call_789",
            "function",
            "getTemperature",
            "{\"city\":\"shanghai\"}"
        );

        FunctionCall toolCall2 = FunctionCall.of(
            "call_790",
            "function",
            "getTemperature",
            "{\"city\":\"beijing\"}"
        );

        Message toolCallMessage = Message.of(
            RoleType.ASSISTANT,
            "",
            null,
            null,
            null,
            List.of(toolCall1, toolCall2)
        );

        CompletionResponse toolCallResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.TOOL_CALLS, toolCallMessage)),
            new Usage(100, 20, 120)
        );

        // Second call: LLM returns final comparison
        Message finalMessage = Message.of(
            RoleType.ASSISTANT,
            "Shanghai has a temperature of 30°C, while Beijing is at 28°C. Shanghai is warmer."
        );

        CompletionResponse finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(180, 40, 220)
        );

        mockLLMProvider.addResponse(toolCallResponse);
        mockLLMProvider.addResponse(finalResponse);

        var agent = Agent.builder()
                .name("weather-agent")
                .description("get weather of a city")
                .systemPrompt("You are an assistant that helps users get weather information.")
                .toolCalls(Functions.from(weatherService, "getTemperature", "getAirQuality"))
                .llmProvider(mockLLMProvider)
                .build();

        String query = "Compare the temperature between shanghai and beijing";
        logger.info("Testing query: {}", query);
        String result = agent.run(query, ExecutionContext.empty());
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        String lowerResult = result.toLowerCase(Locale.ENGLISH);
        assertTrue(lowerResult.contains("shanghai") || lowerResult.contains("beijing"),
                   "Result should contain information about the queried cities");
    }

    @Test
    void testNoFunctionCall() {
        // LLM directly returns answer without calling any function
        Message finalMessage = Message.of(
            RoleType.ASSISTANT,
            "I am a weather toolkit, I don't know other things, so which city's weather do you want to check?"
        );

        CompletionResponse finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(80, 25, 105)
        );

        mockLLMProvider.addResponse(finalResponse);

        var agent = Agent.builder()
                .name("weather-agent")
                .description("get weather of a city")
                .systemPrompt("You are an assistant that helps users get weather information. "
                              + "If the query does not contain a city in the given list, return 'I am a weather toolkit, I don't know other things, so which city's weather do you want to check?'.")
                .toolCalls(Functions.from(weatherService, "getTemperature", "getAirQuality"))
                .llmProvider(mockLLMProvider)
                .build();

        String query = "What is the capital of France?";
        logger.info("Testing query: {}", query);
        String result = agent.run(query, ExecutionContext.empty());
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.toLowerCase(Locale.ENGLISH).contains("don't") || result.toLowerCase(Locale.ENGLISH).contains("which city"),
                   "Result should indicate this is a weather toolkit");
    }
}
