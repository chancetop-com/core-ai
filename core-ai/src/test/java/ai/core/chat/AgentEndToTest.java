package ai.core.chat;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.function.WeatherService;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.tool.function.Functions;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * author: lim chen
 * date: 2025/12/18
 * description:
 */
@Disabled
class AgentEndToTest extends IntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentEndToTest.class);
    @Inject
    LLMProviders llmProviders;
    WeatherService weatherService;


    @BeforeEach
    void before() {
        weatherService = new WeatherService();
    }

    @Test
    void chat() {
        var agent = Agent.builder()
                .llmProvider(llmProviders.getProvider())
                .model("deepseek-chat")
                .build();
        var out = agent.run("Hello", ExecutionContext.builder().build());
        LOGGER.info(out);
    }

    @Test
    void chatSlashCommand() {
        var agent = Agent.builder()
                .llmProvider(llmProviders.getProvider())
                .model("gpt-4o")
                .toolCalls(Functions.from(weatherService))
                .slidingWindowConfig(null)
                .build();
        var out = agent.run("/slash_command:getTemperaturePro:{\"city\": \"xiamen\"}", ExecutionContext.builder().build());
        LOGGER.info(out);
        // The tool will not be called again, and the text will be polished.
        out = agent.run("What's the weather like in Xiamen?", ExecutionContext.builder().build());
        LOGGER.info(out);
    }

    @Test
    void chatTool() {
        var agent = Agent.builder()
                .llmProvider(llmProviders.getProvider())
                .toolCalls(Functions.from(weatherService))
                .slidingWindowConfig(null)
                .build();
        // The tool will not be called again, and the text will be polished.
        var out = agent.run("What's the weather like in Xiamen?", ExecutionContext.builder().build());
        LOGGER.info(out);
    }

    @Test
    void chatProvider() {
        var llm = llmProviders.getProvider();
        var result = llm.completion(CompletionRequest.of(List.of(Message.of(RoleType.USER, "hello")), null, 0.0, "gpt-4o", "chat"));
        LOGGER.info(JsonUtil.toJson(result));
    }

    @Test
    void chatProviderStream() {
        var llm = llmProviders.getProvider();
        llm.completionStream(CompletionRequest.of(List.of(Message.of(RoleType.USER, "Write a bubble sort algorithm")), null, 0.0, "gpt-4o", "chat"), new StreamingCallback() {
            @Override
            public void onChunk(String chunk) {
                LOGGER.info(chunk);
            }
            @Override
            public void onComplete() {
                LOGGER.info("-----------");
            }
            @Override
            public void onError(Throwable error) { }
        });

    }

    @Test
    void chatStream() {
        var agent = Agent.builder()
                .llmProvider(llmProviders.getProvider())
                .toolCalls(Functions.from(weatherService))
                .slidingWindowConfig(null)
                .streamingCallback(new StreamingCallback() {
                    @Override
                    public void onChunk(String chunk) {
                        LOGGER.info(chunk);
                    }
                    @Override
                    public void onComplete() {
                        LOGGER.info("---------------");
                    }
                    @Override
                    public void onError(Throwable error) { }
                })
                .build();
        agent.run("What's the weather like in Xiamen?", ExecutionContext.builder().build());
    }


}
