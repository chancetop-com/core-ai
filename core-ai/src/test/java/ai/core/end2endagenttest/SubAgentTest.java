package ai.core.end2endagenttest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.defaultagents.DefaultExploreAgent;
import ai.core.llm.LLMProviders;
import core.framework.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
@Disabled
class SubAgentTest extends IntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubAgentTest.class);
    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        var exploreAgent = DefaultExploreAgent.of(llmProviders.getProvider());

        var agent = Agent.builder()
                .name("sub-agent-test-agent")
                .description("This agent is used to test sub agent functionality.")
                .systemPrompt("""
                        use explore agent to explore the workspace and find the answer to user question
                        """)
                .model("gpt-5-mini")
                .subAgents(List.of(exploreAgent.toSubAgentToolCall(DefaultExploreAgent.ExploreAgentContext.class)))
                .llmProvider(llmProviders.getProvider()).build();

        var rst = agent.run("how to define an api in core-ng framework?", ExecutionContext.builder().customVariables(Map.of("workspace", "d:\\core-ng-project")).build());
        LOGGER.info(rst);
    }
}
