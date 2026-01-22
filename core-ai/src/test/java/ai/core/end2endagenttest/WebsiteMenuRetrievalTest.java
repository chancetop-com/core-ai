package ai.core.end2endagenttest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import core.framework.inject.Inject;
import core.framework.util.Strings;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
@Disabled
class WebsiteMenuRetrievalTest extends IntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebsiteMenuRetrievalTest.class);
    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        var agent = Agent.builder()
                .name("website-menu-retrieval-agent")
                .description("This agent is used to test retrieve website menu information budget.")
                .systemPrompt("""
                        use tools to retrieve the menu and price from the website provided by user, choose a default location for price.
                        if you can not access the website, or you are not able to find the menu information, respond with 'unable to retrieve menu'.
                        """)
                .model("gpt-4.1")
                .mcpServers(List.of("playwright"))
                .llmProvider(llmProviders.getProvider()).build();

        var rst = agent.run("https://www.subway.com/", ExecutionContext.empty());
        LOGGER.info(rst);
        LOGGER.info(Strings.format("agent token cost: total={}, input={}, output={}",
                agent.getCurrentTokenUsage().getTotalTokens(),
                agent.getCurrentTokenUsage().getPromptTokens(),
                agent.getCurrentTokenUsage().getCompletionTokens()));
    }
}
