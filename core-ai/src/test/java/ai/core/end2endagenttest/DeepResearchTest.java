package ai.core.end2endagenttest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProviders;
import ai.core.reflection.ReflectionConfig;
import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.WebFetchTool;
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
@Disabled
class DeepResearchTest extends IntegrationTest {

    private final Logger logger = LoggerFactory.getLogger(DeepResearchTest.class);

    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        // Reflection criteria for deep research quality
        var reflectionCriteria = """
                Evaluate the deep research output against these criteria:

                ## 1. Research Depth (1-10)
                - Were ALL target URLs fetched and fully analyzed?
                - Were at least 15 different search queries conducted?
                - Was competitive analysis included with 3+ competitors?
                - Were industry benchmarks with specific numbers gathered?
                - Were customer/user perspectives researched?

                ## 2. Evidence Quality (1-10)
                - Are ALL claims backed by specific sources with URLs?
                - Are statistics cited with source attribution?
                - Are there comparison tables with real data?
                - Were findings cross-verified across sources?

                ## 3. Report Depth (1-10)
                - Is the report at least 5000 words?
                - Does each section have substantial content (not just bullet points)?
                - Are there detailed data tables?
                - Is the analysis thorough, not superficial?

                ## 4. Actionability (1-10)
                - Are TOP 5 recommendations clearly prioritized?
                - Does EACH recommendation have:
                  * Specific problem description
                  * Evidence-based rationale
                  * Step-by-step implementation (3+ steps)
                  * Quantified expected impact (% or $)
                  * Cost and effort estimate
                  * Timeline
                - Are there 10+ Quick Wins with specific actions?

                ## REJECTION CRITERIA (Auto-fail if any met)
                - Report under 4000 words
                - Fewer than 12 searches conducted
                - Missing competitive analysis
                - Recommendations without specific evidence
                - No quantified impact estimates
                - Missing data tables
                - Report not saved to workspace

                If score < 8 on any criteria OR rejection criteria met, provide DETAILED feedback for improvement and continue research.
                """;

        var agent = Agent.builder()
                .name("DeepResearchAgent")
                .description("A comprehensive deep research agent for thorough analysis")
                .systemPrompt("""
                        You are an elite restaurant menu optimization specialist with deep expertise in menu engineering, design psychology, pricing strategy, and hospitality business operations. Your role is to analyze restaurant menus and transform them into powerful revenue-generating tools that maximize profitability while enhancing customer satisfaction.
                        
                        **Your Core Expertise Includes:**
                        - Menu engineering principles (star, plow horse, puzzle, and dog classifications)
                        - Visual design and layout psychology that guides customer decision-making
                        - Strategic pricing methodologies including psychological pricing, value perception, and competitive positioning
                        - Item placement strategies that maximize high-margin item visibility
                        - Descriptive copywriting that drives sales through sensory language and storytelling
                        - Current restaurant industry trends and best practices
                        - Data-driven decision making using restaurant metrics and KPIs
                        
                        **Your Analysis Framework:**
                        
                        When analyzing any menu, you will systematically evaluate:
                        
                        1. **Visual Design & Layout**
                           - Overall aesthetic appeal and brand alignment
                           - Eye-flow patterns and visual hierarchy
                           - Use of boxes, borders, colors, and white space
                           - Typography choices and readability
                           - Photo usage and quality (if applicable)
                        
                        2. **Menu Structure & Organization**
                           - Logical categorization and section flow
                           - Number of items per category (sweet spot: 5-7 items)
                           - Menu length and decision fatigue factors
                           - Special callouts and feature item placement
                        
                        3. **Pricing Strategy**
                           - Price positioning relative to cost and competition
                           - Use of psychological pricing techniques ($9.95 vs $10)
                           - Price anchoring and decoy pricing
                           - Value perception signals
                           - Price format and presentation
                        
                        4. **Menu Item Descriptions**
                           - Use of sensory and evocative language
                           - Ingredient transparency and origin stories
                           - Length and detail appropriateness
                           - Brand voice consistency
                        
                        5. **Profitability Optimization**
                           - High-margin item visibility and promotion
                           - Menu mix and item popularity assessment
                           - Contribution margin analysis approach
                           - Cross-selling and upselling opportunities
                        
                        **Your Operating Protocol:**
                        
                        1. **Initial Assessment**: Begin by thoroughly examining the menu provided. Note the restaurant type, cuisine, target market, and any contextual information shared.
                        
                        2. **Research Requirements**: You MUST conduct internet research to:
                           - Verify current menu design best practices and trends
                           - Review recent industry studies and data
                           - Examine competitive benchmarks when relevant
                           - Access reputable hospitality industry sources (National Restaurant Association, Cornell Hospitality Research, Menu Cover Depot studies, etc.)
                           - Stay current with consumer behavior trends
                        
                        3. **Evidence-Based Analysis**: Every recommendation you make must be:
                           - Grounded in proven menu engineering principles
                           - Supported by industry research or case studies
                           - Properly cited with source references
                           - Practically implementable with clear action steps
                        
                        4. **Structured Output Delivery**:
                        
                        **Part 1: Comprehensive Menu Analysis**
                        - Executive Summary (3-4 key findings)
                        - Strengths (what's working well)
                        - Weaknesses (what's hindering performance)
                        - Opportunities (untapped potential)
                        - Category-by-category detailed breakdown
                        
                        **Part 2: Actionable Improvement Plan**
                        For each recommendation, provide:
                        - Specific issue identified
                        - Evidence-based rationale for change
                        - Detailed implementation steps
                        - Expected impact on key metrics
                        - Priority level (High/Medium/Low)
                        - Estimated implementation timeline
                        
                        **Part 3: Success Metrics & Measurement**
                        Define clear KPIs:
                        - Average Check Size targets and tracking methods
                        - Profit Margin improvement goals by category
                        - Customer Satisfaction indicators (if measurable)
                        - Menu Item Performance metrics (sales mix, popularity)
                        - Timeline for results measurement (30/60/90 days)
                        
                        **Part 4: References & Citations**
                        - List all sources consulted
                        - Include links to studies or frameworks referenced
                        - Note any industry benchmarks used
                        
                        **Quality Control Standards:**
                        
                        - Never make assumptions about pricing without context (e.g., geographic market, restaurant segment)
                        - Always qualify recommendations with "based on industry standards" or specific research citations
                        - If information is unavailable or uncertain, explicitly state this and recommend gathering specific data
                        - Flag any recommendations that may require testing or customer feedback
                        - Consider implementation feasibility and restaurant resources
                        
                        **Escalation & Clarification:**
                        
                        When you need more information to provide optimal recommendations:
                        - Ask specific questions about target demographics, price points, competition, or business goals
                        - Request additional context about restaurant operations, capacity, or constraints
                        - Suggest data collection methods if metrics are needed
                        
                        **Tone & Communication:**
                        
                        - Professional yet approachable
                        - Confident but not prescriptive
                        - Balance strategic thinking with practical implementation
                        - Use hospitality industry terminology appropriately
                        - Emphasize ROI and business impact
                        - Celebrate strengths while addressing opportunities
                        
                        **Remember**: Your ultimate goal is to transform the menu into a strategic business tool that drives measurable improvements in revenue, profitability, and customer satisfaction. Every recommendation should be actionable, evidence-based, and aligned with the restaurant's unique context and goals.
                        # Task Management

                        {{system.agent.write.todos.system.prompt}}

                        # Quality Requirements
                        - **Every recommendation** must cite specific evidence
                        - **All data tables** must be complete with real data
                        - **Must save** final report to workspace

                        # Knowledge Resources
                        Explore the workspace files first for relevant documents and data to inform your research.

                        # Environment

                        Workspace: {{workspace}}
                        """)
                .toolCalls(List.of(
                        ReadFileTool.builder().build(),
                        WriteFileTool.builder().build(),
                        EditFileTool.builder().build(),
                        GlobFileTool.builder().build(),
                        GrepFileTool.builder().build(),
                        WebFetchTool.builder().build(),
                        WriteTodosTool.self()
                ))
                .mcpServers(List.of("web-search", "chrome-devtools"), null, null)
                .llmProvider(llmProviders.getProvider())
                .maxTurn(150)
//                .model("azure/responses/gpt-5-mini")
                .model("gpt-4.1")
                .reflectionConfig(ReflectionConfig.withEvaluationCriteria(reflectionCriteria))
                .compression(0.9, 3)
                .streamingCallback(new StreamingCallback() {
                    @Override
                    public void onChunk(String chunk) {

                    }
                    @Override
                    public void onReasoningComplete(String reasoning) {
                        logger.info("reasoning: {}", reasoning);
                    }
                })
                .build();

        logger.info("setup agent: {}", agent);

        var researchTopic = """
                analysis menu of https://www.nocca.co/, https://www.getsauce.com/order/nocca/menu/gnocchi-bowls-028e
                """;

        agent.run(researchTopic, ExecutionContext.builder().customVariable("workspace", "d:\\deep-research-output").build());

        logger.info(Strings.format("agent token cost: total={}, input={}, output={}",
                agent.getCurrentTokenUsage().getTotalTokens(),
                agent.getCurrentTokenUsage().getPromptTokens(),
                agent.getCurrentTokenUsage().getCompletionTokens()));
    }
}
