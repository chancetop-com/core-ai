package ai.core.chat;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.function.WeatherService;
import ai.core.llm.LLMProviders;
import ai.core.tool.function.Functions;
import core.framework.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * author: lim chen
 * date: 2025/12/18
 * description:
 */
@Disabled
public class AgentEndToTest extends IntegrationTest {
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
                .build();
        var out = agent.run("Hello", ExecutionContext.builder().build());
        System.out.println(out);
    }

    @Test
    void chatSlashCommand() {
        var agent = Agent.builder()
                .llmProvider(llmProviders.getProvider())
                .toolCalls(Functions.from(weatherService))
                .slidingWindowConfig(null)
                .build();
        var out = agent.run("/slash_command:getTemperaturePro:{\"city\": \"xiamen\"}", ExecutionContext.builder().build());
        LOGGER.info(out);
        // The tool will not be called again, and the text will be polished.
        out = agent.run("What's the weather like in Xiamen?", ExecutionContext.builder().build());
        LOGGER.info(out);


    }


}
