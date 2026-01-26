package ai.core.end2endagenttest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.tool.tools.CaptionImageTool;
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
//@Disabled
class MultiModalTest extends IntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultiModalTest.class);
    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        var agent = Agent.builder()
                .name("multi-module-test-agent")
                .description("an agent to test multi module setup")
                .systemPrompt("""
                        use playwright to open a browser and navigate to user's website, then take a screenshot with fullPage to true, finally summarize the text content for the user.
                        """)
                .model("gpt-5-mini")
                .toolCalls(List.of(ReadFileTool.builder().build()))
                .mcpServers(List.of("playwright"))
                .llmProvider(llmProviders.getProvider()).build();
        var rst = agent.run("url: https://mrkeke.connexup-uat.online/, do not ask anything else", ExecutionContext.builder().build());
        LOGGER.info(rst);
    }

    @Test
    void testImageInContext() {
        var agent = Agent.builder()
                .name("multi-module-test-agent")
                .description("an agent to test multi module setup")
                .model("gpt-5-mini")
                .llmProvider(llmProviders.getProvider()).build();
        var rst = agent.run("what is this image about?", ExecutionContext.builder().attachedContent(ExecutionContext.AttachedContent.of("https://fbrdevstorage.blob.core.windows.net/static/fbr-uat/product/file/740c5950e5064b81b2c1f34a2a460a08.jpg", ExecutionContext.AttachedContent.AttachedContentType.IMAGE)).build());
        LOGGER.info(rst);
    }

    @Test
    void testImageInQuery() {
        var agent = Agent.builder()
                .name("multi-module-test-agent")
                .description("an agent to test multi module setup")
                .model("gpt-5-mini")
                .toolCalls(List.of(CaptionImageTool.builder().build()))
                .llmProvider(llmProviders.getProvider()).build();
        var rst = agent.run("what is this image about?\nhttps://fbrdevstorage.blob.core.windows.net/static/fbr-uat/product/file/740c5950e5064b81b2c1f34a2a460a08.jpg", ExecutionContext.empty());
        LOGGER.info(rst);
    }
}
