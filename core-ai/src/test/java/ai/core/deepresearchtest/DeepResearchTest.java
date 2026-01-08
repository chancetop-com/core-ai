package ai.core.deepresearchtest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.compression.Compression;
import ai.core.llm.LLMProviders;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.WriteFileTool;
import ai.core.tool.tools.WriteTodosTool;
import core.framework.inject.Inject;
import core.framework.util.Strings;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Xander
 */
//@Disabled
class DeepResearchTest extends IntegrationTest {

    private final Logger logger = LoggerFactory.getLogger(DeepResearchTest.class);

    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        Compression customCompression = new Compression(0.8, 3, llmProviders.getProvider(), "gpt-5-mini");
        var agent = Agent.builder()
                .name("DeepResearchAgent")
                .description("An agent for deep research tasks using web search")
                .systemPrompt("""
                        You are an elite Deep Research Specialist with expertise in systematic information gathering, critical analysis, and comprehensive report synthesis. Your mission is to conduct thorough, evidence-based research that delivers actionable insights.

                        # Core Competencies

                        - **Research Methodology**: Systematic literature review, competitive analysis, trend identification
                        - **Critical Analysis**: Source evaluation, bias detection, fact verification, cross-referencing
                        - **Synthesis Skills**: Pattern recognition, insight extraction, logical structuring
                        - **Domain Adaptability**: Capable of researching any topic from technology to business to science

                        # Available Tools

                        1. **tavily_search**: Web search for current information, trends, and data
                           - Use specific, targeted queries (e.g., "AI agent frameworks comparison 2024" not "AI agents")
                           - Combine keywords strategically for better results
                           - Use `search_depth: "advanced"` for comprehensive coverage
                           - Use `topic: "news"` for recent developments, `topic: "general"` for broad coverage

                        2. **write_todos**: Track research progress and organize workflow
                           - Break complex research into discrete, manageable tasks
                           - Mark tasks as IN_PROGRESS when starting, COMPLETED when done
                           - Add new tasks as research reveals additional areas to explore

                        3. **write_file**: Save final research report
                           - Use markdown format for clear structure
                           - Include all citations and references

                        4. **read_file**: Read existing files for context if needed

                        # Research Protocol

                        ## Phase 1: Planning (Use write_todos)
                        1. Analyze the research question to identify key dimensions
                        2. Decompose into 4-6 specific sub-questions
                        3. Plan search queries for each dimension
                        4. Identify what "good research" looks like for this topic

                        ## Phase 2: Information Gathering
                        For each sub-question:
                        1. Execute 2-3 targeted searches with different query angles
                        2. Prioritize recent sources (last 1-2 years) for current state
                        3. Include historical context where relevant
                        4. Note conflicting information for later analysis
                        5. Mark todo as completed before moving to next

                        ## Phase 3: Analysis & Synthesis
                        1. Identify patterns and themes across sources
                        2. Evaluate source credibility and potential biases
                        3. Reconcile conflicting information
                        4. Extract actionable insights
                        5. Formulate evidence-based conclusions

                        ## Phase 4: Report Generation
                        1. Structure findings logically
                        2. Write clear, concise prose
                        3. Include all citations
                        4. Save to workspace

                        # Output Format

                        ```markdown
                        # [Research Topic]

                        ## Executive Summary
                        - 3-5 bullet points of key findings
                        - Most important insight highlighted

                        ## 1. [First Major Theme]
                        ### Current State
                        [Evidence-based analysis with citations]

                        ### Key Players/Examples
                        [Specific examples with sources]

                        ### Implications
                        [What this means for the topic]

                        ## 2. [Second Major Theme]
                        [Same structure...]

                        ## 3. [Additional Themes as needed]

                        ## Challenges & Limitations
                        - Challenge 1: [Description] [Source]
                        - Challenge 2: [Description] [Source]

                        ## Future Outlook
                        - Short-term (6-12 months): [Predictions with evidence]
                        - Medium-term (1-3 years): [Trends and projections]

                        ## Conclusions & Recommendations
                        1. [Actionable recommendation based on findings]
                        2. [Second recommendation]
                        3. [Third recommendation]

                        ## References
                        1. [Source Title](URL) - Brief description
                        2. [Source Title](URL) - Brief description
                        ...
                        ```

                        # Quality Standards

                        - **Evidence-Based**: Every claim must have a source citation
                        - **Current**: Prioritize information from the last 12-24 months
                        - **Balanced**: Present multiple perspectives, note disagreements
                        - **Actionable**: Conclusions should inform decision-making
                        - **Verified**: Cross-reference key facts across multiple sources

                        # Critical Guidelines

                        1. ALWAYS use tavily_search before making any claims - never rely on prior knowledge alone
                        2. ALWAYS cite sources with URLs in the final report
                        3. ALWAYS use write_todos to track progress systematically
                        4. If search results are insufficient, try alternative query formulations
                        5. If sources conflict, note the disagreement and explain possible reasons
                        6. Distinguish between facts, expert opinions, and speculation

                        # Task Management

                        {{system.agent.write.todos.system.prompt}}

                        # Environment

                        Workspace: {{workspace}}
                        """)
                .toolCalls(List.of(
                        ReadFileTool.builder().build(),
                        WriteFileTool.builder().build(),
                        WriteTodosTool.self()
                ))
                .mcpServers(List.of("web-search"), List.of("tavily_search"))
                .llmProvider(llmProviders.getProvider())
                .maxTurn(100)
                .model("gpt-5-mini")
                .compression(customCompression)
                .build();

        logger.info("setup agent: {}", agent);

        var researchTopic = """
                Analysis two site https://www.nocca.co/， https://www.getsauce.com/order/nocca/menu/gnocchi-bowls-028e
                
                **Critical Operating Principles:**
                - Always browse the internet for the comprehensive and latest information and trends when needed
                - Always verify information from reputable sources and cite properly
                - Never make assumptions without verification
                - Provide actionable recommendations with clear implementation steps
                - Focus on practical application of menu design principles
                
                **Output Requirements:**
                For each menu analysis request, provide:
                1. Comprehensive Menu Analysis: A detailed breakdown of the current menu, identifying strengths, weaknesses, and areas for improvement.
                2. Actionable Improvement Plan: A step-by-step guide on how to enhance the menu, including design changes, pricing strategies, and item placement.
                3. Success Metrics: Clear KPIs to measure the effectiveness of the implemented changes, aligned with the success metrics outlined blow.
                4. References: Cite any external sources or frameworks used in your analysis.
                5. Output should be concise and clear, highlighting key points for restaurant managers to quickly understand and implement.
                
                **Success Metrics**
                The **success metrics** for your analysis and actions include:
                1. Increased Average Check Size: Recommendations should aim to increase the average spend per customer through strategic menu design and pricing.
                2. Enhanced Profit Margins: Suggestions should focus on improving the profitability of menu items by optimizing pricing strategies and item placement.
                3. Improved Customer Satisfaction: The menu should be designed to enhance the overall dining experience, making it easier for customers to make choices and find appealing options.
                
                The ultimate goal is to transform the restaurant's menu into a powerful tool that drives revenue growth, boosts profitability, and elevates customer satisfaction.\s
                
                
                **Quality Standards:**
                Every action you recommend must demonstrate expert-level understanding, practical applicability, and rigorous fact-checking. You should also provide citations for any data or frameworks referenced from external sources.
                """;

        agent.run(Strings.format("""
                Conduct comprehensive research on the following topic:

                {}
                """, researchTopic), ExecutionContext.builder().customVariable("workspace", "/tmp/deep-research-output").build());

        logger.info(Strings.format("agent token cost: total={}, input={}, output={}",
                agent.getCurrentTokenUsage().getTotalTokens(),
                agent.getCurrentTokenUsage().getPromptTokens(),
                agent.getCurrentTokenUsage().getCompletionTokens()));
    }
}
