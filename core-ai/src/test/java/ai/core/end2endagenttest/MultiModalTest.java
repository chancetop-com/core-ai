package ai.core.end2endagenttest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.tool.tools.CaptionImageTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.SummarizePdfTool;
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
                .model("gpt-5.1")
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
                .model("gpt-5.1")
                .llmProvider(llmProviders.getProvider()).build();
        var rst = agent.run("what is this image about?", ExecutionContext.builder().attachedContent(ExecutionContext.AttachedContent.ofUrl("https://fbrdevstorage.blob.core.windows.net/static/fbr-uat/product/file/740c5950e5064b81b2c1f34a2a460a08.jpg", ExecutionContext.AttachedContent.AttachedContentType.IMAGE)).build());
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

    @Test
    void testPdfInContextByUrl() {
        var agent = Agent.builder()
                .name("multi-module-test-agent")
                .description("an agent to test PDF input")
                .model("gpt-5.1")
                .llmProvider(llmProviders.getProvider()).build();
        var context = ExecutionContext.builder()
                .attachedContent(ExecutionContext.AttachedContent.ofUrl(
                        "https://fbrdevbostorage.blob.core.windows.net/static/ai/16956b63-7b95-4851-996f-0acc9166973d.pdf",
                        ExecutionContext.AttachedContent.AttachedContentType.PDF))
                .build();
        var rst = agent.run("Please summarize the content of this PDF document.", context);
        LOGGER.info(rst);
    }

    @Test
    void testPdfInQueryByTool() {
        var agent = Agent.builder()
                .name("multi-module-test-agent")
                .description("an agent to test PDF input via tool")
                .systemPrompt("You have access to a tool that can read PDF documents. When the user provides a PDF URL, use the summarize_pdf tool to read and analyze its content.")
                .model("gpt-5.1")
                .toolCalls(List.of(SummarizePdfTool.builder().build()))
                .llmProvider(llmProviders.getProvider()).build();
        var rst = agent.run("Please summarize this PDF document: https://fbrdevbostorage.blob.core.windows.net/static/ai/16956b63-7b95-4851-996f-0acc9166973d.pdf", ExecutionContext.empty());
        LOGGER.info(rst);
    }

}
