package ai.core.end2endagenttest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.tool.tools.CaptionImageTool;
import ai.core.tool.tools.ReadFileTool;
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
                .systemPrompt(Strings.format("""
                        You are a web scraping agent that uses a browser to navigate websites and extract restaurant menu information.
                        
                        user the stealth-browser-mcp to open a browser and navigate to the restaurant's webpage provided by the user.
                        always use headless=false to open the browser so that you can see the browser actions.
                        do not block any resources when opening the webpage.

                        # Your Task
                        Extract the COMPLETE menu with ALL categories and ALL items with prices. A partial menu is NOT acceptable.

                        # MANDATORY Workflow for Menu Extraction

                        1. **Navigate to the page** using new_page tool
                        2. **Scroll down** within each category to ensure all items are loaded (lazy loading)
                        3. **Identify Menu Structure**: Look for HTML elements that represent menu categories and items. Categories are often in header tags (e.g., <h2>, <h3>), while items may be in list tags (<li>) or divs with specific classes.
                        4. **AJAX Handling**: If the menu loads via AJAX, and cannot be accessed directly, use list_network_requests,get_response_content tools to capture the AJAX requests and extract the menu data from the responses.
                        5. **Goto AJAX HANDLING** if you are unable to access the menu directly from the webpage after 3 times tools calls.

                        **DO NOT** output explanations like "I cannot access" or "I'm unable to proceed".
                        **ALWAYS** call a tool to continue making progress.

                        # Output Format

                        Return the menu in a structured format with:
                        - Category name
                        - List of items under each category (name + price)
                        """))
                .model("gpt-5.1")
                .mcpServers(List.of("stealth-browser-mcp"))
                .toolCalls(List.of(ReadFileTool.builder().build(), CaptionImageTool.builder().build()))
                .llmProvider(llmProviders.getProvider())
                .maxTurn(40)
                .build();

        var rst = agent.run("https://zandyrestaurant.getbento.com/online-ordering/z-and-y-restaurant/menu", ExecutionContext.empty());
        LOGGER.info(rst);
        LOGGER.info(Strings.format("agent token cost: total={}, input={}, output={}",
                agent.getCurrentTokenUsage().getTotalTokens(),
                agent.getCurrentTokenUsage().getPromptTokens(),
                agent.getCurrentTokenUsage().getCompletionTokens()));
    }
}

