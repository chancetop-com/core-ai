package ai.core.multimoduletest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.tool.tools.ReadFileTool;
import core.framework.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
@Disabled
class MultiModuleTest extends IntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(MultiModuleTest.class);
    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        var agent = Agent.builder()
                .name("multi-module-test-agent")
                .description("an agent to test multi module setup")
                .systemPrompt("""
                        use playwright to open a browser and navigate to user's website, then take a screenshot image and save to d:\\ and use read_file tool to extract text from the image, finally summarize the text content for the user.
                        """)
                .model("gpt-5-mini")
                .toolCalls(List.of(ReadFileTool.builder().build()))
                .mcpServers(List.of("playwright"))
                .llmProvider(llmProviders.getProvider()).build();
        var rst = agent.run("url: https://mrkeke.connexup-uat.online/, do not ask anything else", ExecutionContext.builder().build());
        log.info(rst);
    }
}
