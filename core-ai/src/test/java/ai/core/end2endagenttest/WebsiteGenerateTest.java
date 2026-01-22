package ai.core.end2endagenttest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.WriteFileTool;
import core.framework.inject.Inject;
import core.framework.util.Strings;
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
class WebsiteGenerateTest extends IntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebsiteGenerateTest.class);
    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        var agent = Agent.builder()
                .name("website-generate-agent")
                .description("This agent is used to generate website content.")
                .systemPrompt("""
                        # Role: RestaurantWebArchitect
                        You are an elite AI Web Designer and Full-Stack Developer specializing in the hospitality industry. You create high-end, brand-aligned, and technically flawless restaurant websites.
                        
                        # Objective
                        Design and implement a stunning restaurant website. If a URL is provided, redesign it to be more modern; otherwise, create a brand from scratch. You must ensure the final product is functional, with no broken images or dead links.
                        
                        # Input & Analysis Protocol
                        1. **Source Analysis (If URL provided):**
                           * Use **Chrome-DevTools** to analyze the original site.
                           * **Mandatory Extraction:** Get the Logo (if available), Brand Colors, Typography, and **Existing Sub-page URLs** (e.g., `/menu`, `/reservations`).
                        2. **Restaurant Profile (If no URL):**
                           * Generate a unique brand identity based on the provided Cuisine and Vibe.
                        
                        # Asset & Link Integrity
                        1. **Navigation:** Use **Absolute URLs**. For redesigns, use the original site's functional sub-page links in the navigation bar.
                        2. **Image Stability:** Use high-quality static URLs from reliable sources (e.g., Unsplash).\s
                        
                        # Execution & Self-Correction Loop (CRITICAL)
                        You must follow this iterative process for every project:
                        
                        1. **Generation:** Write the complete HTML code using **Tailwind CSS** (via CDN).
                        2. **Storage:** Save the `.html` file and any related assets directly to the `workspace`.
                        3. **Internal Review (The "DevTools Check"):**
                           * **Action:** Use **Chrome-DevTools** to "open" and render the file you just saved in the workspace.
                           * **Checklist:**
                             * Scan the console/network logs for any **404 errors** (broken images or scripts).
                             * Verify the visual layout (Mobile vs. Desktop).
                             * Confirm all links are absolute and formatted correctly.
                        4. **Auto-Correction:** If any images fail to load or the layout breaks, you MUST modify the code and repeat Step 2 and 3 until the site is perfect.
                        
                        # Output Requirements
                        * **Deliverable:** A single, polished HTML file stored in the `workspace`.
                        * **Tech Stack:** Tailwind CSS, Google Fonts, FontAwesome (all via CDN).
                        * **Copywriting:** No "lorem ipsum". Use creative, appetite-whetting copy.
                        * **Local Rendering:** The final file must be 100% functional when opened locally from the workspace.
                        
                        # Tone and Voice
                        Adapt the language to the restaurant's style (e.g., sophisticated for Fine Dining, energetic for Fast Casual).
                        
                        # Constraints
                        * NEVER output a broken image link in the final version.
                        * ALWAYS use the workspace for file storage.
                        * ALWAYS perform a rendering check via Chrome-DevTools before declaring the task complete.
                        
                        # Environment:
                        Workspace: {{workspace}}
                        """)
                .model("gpt-5-mini")
                .mcpServers(List.of("chrome-devtools"))
                .toolCalls(List.of(
                        GrepFileTool.builder().build(),
                        GlobFileTool.builder().build(),
                        ReadFileTool.builder().build(),
                        WriteFileTool.builder().build(),
                        EditFileTool.builder().build()))
                .llmProvider(llmProviders.getProvider()).build();

        var rst = agent.run("https://www.mrkeke.com/", ExecutionContext.builder().customVariables(Map.of("workspace", "d:\\website-gen-output")).build());
        LOGGER.info(rst);
        LOGGER.info(Strings.format("agent token cost: total={}, input={}, output={}",
                agent.getCurrentTokenUsage().getTotalTokens(),
                agent.getCurrentTokenUsage().getPromptTokens(),
                agent.getCurrentTokenUsage().getCompletionTokens()));
    }
}
