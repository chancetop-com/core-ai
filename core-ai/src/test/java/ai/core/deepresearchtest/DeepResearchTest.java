package ai.core.deepresearchtest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.compression.Compression;
import ai.core.llm.LLMProviders;
import ai.core.reflection.ReflectionConfig;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.WriteFileTool;
import ai.core.tool.tools.WriteTodosTool;
import core.framework.inject.Inject;
import core.framework.util.Strings;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Xander
 */
class DeepResearchTest extends IntegrationTest {

    private final Logger logger = LoggerFactory.getLogger(DeepResearchTest.class);

    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        Compression customCompression = new Compression(0.8, 3, llmProviders.getProvider(), "gpt-4.1");

        var reflectionCriteria = """
                Evaluate the deep research output against these RESEARCH QUALITY criteria:

                ## 1. Research Depth Score (1-10)
                - Did the agent complete ALL 6 research phases?
                - Were at least 15-20 different searches conducted?
                - Is there evidence of multi-angle research (not just surface-level)?
                - Are there insights from competitor analysis, industry data, AND academic sources?

                ## 2. Evidence Quality Score (1-10)
                - Are all major claims backed by cited sources?
                - Are sources diverse (not all from the same website)?
                - Are statistics and data points properly attributed?
                - Is there cross-verification of key findings?

                ## 3. Analysis Depth Score (1-10)
                - Does the analysis go beyond obvious observations?
                - Are there novel insights that wouldn't be apparent from a quick review?
                - Is there quantitative analysis (pricing comparison, menu item count, etc.)?
                - Are recommendations backed by specific research findings?

                ## 4. Actionability Score (1-10)
                - Are recommendations specific and implementable?
                - Does each recommendation have expected ROI?
                - Are quick wins separated from strategic changes?
                - Is there a clear prioritization?

                ## REJECTION CRITERIA (Must fix before approval)
                - Fewer than 15 searches conducted
                - Missing any of the 6 research phases
                - No competitor analysis included
                - No industry benchmark data
                - Recommendations without supporting evidence
                - Report shorter than 3000 words

                SCORING:
                - If ANY score < 7, provide specific feedback for deeper research
                - If ANY rejection criteria met, agent MUST conduct additional research
                """;

        var agent = Agent.builder()
                .name("DeepMenuResearchAgent")
                .description("A deep research agent specializing in comprehensive restaurant menu analysis")
                .systemPrompt("""
                        You are a Deep Research Agent specializing in restaurant menu optimization. Your task is to conduct COMPREHENSIVE, THOROUGH research - not quick analysis. Plan for 20-30 minutes of research time.

                        # Your Mission
                        Conduct exhaustive research on the target restaurant's menu, gathering data from multiple sources to provide evidence-based, actionable recommendations that will measurably increase revenue.

                        # Research Philosophy
                        - **Depth over speed**: Take time to gather comprehensive data
                        - **Evidence-based**: Every recommendation must be backed by research
                        - **Multi-source verification**: Cross-reference findings across sources
                        - **Quantitative focus**: Find specific numbers, benchmarks, and data points

                        # MANDATORY Research Protocol (6 Phases)

                        You MUST complete ALL 6 phases. Use write_todos to track progress through each phase.

                        ## Phase 1: Target Menu Deep Dive
                        Research the target restaurant's current menu in detail:
                        - Search for the restaurant name + menu + reviews
                        - Search for the restaurant on food delivery platforms
                        - Search for customer reviews mentioning specific menu items
                        - Search for the restaurant's social media presence
                        - Search for any press coverage or blog reviews
                        - Document: All menu items, prices, descriptions, categories

                        ## Phase 2: Competitor Analysis
                        Research direct competitors in the same market:
                        - Search for "best [cuisine type] restaurants [location]"
                        - Search for "[cuisine] restaurants near [location] menu prices"
                        - Search for competitor restaurant menus specifically
                        - Search for competitor reviews and what customers praise
                        - Document: Competitor pricing, menu structure, popular items, differentiators

                        ## Phase 3: Industry & Market Data
                        Research industry trends and benchmarks:
                        - Search for "[cuisine type] restaurant industry trends 2024 2025"
                        - Search for "restaurant menu pricing trends [year]"
                        - Search for "average check size [cuisine] restaurant"
                        - Search for "fast casual restaurant profit margins menu"
                        - Document: Industry benchmarks, pricing trends, consumer preferences

                        ## Phase 4: Menu Psychology & Best Practices
                        Research menu optimization strategies:
                        - Search for "menu engineering best practices restaurant"
                        - Search for "restaurant menu design increase sales"
                        - Search for "menu pricing psychology research"
                        - Search for "menu description words increase orders"
                        - Document: Proven techniques with data on effectiveness

                        ## Phase 5: Consumer Behavior Research
                        Research customer decision-making:
                        - Search for "restaurant customer ordering behavior research"
                        - Search for "online food ordering customer preferences"
                        - Search for "[cuisine] food trends consumer preferences"
                        - Document: What drives customer choices, pain points, preferences

                        ## Phase 6: Case Studies & Success Stories
                        Research real-world examples:
                        - Search for "restaurant menu redesign case study results"
                        - Search for "menu optimization success story revenue increase"
                        - Search for "[cuisine] restaurant increased sales menu changes"
                        - Document: Specific examples with quantified results

                        # Search Strategy Guidelines

                        For each search:
                        - Use `search_depth: "advanced"` for comprehensive results
                        - Try multiple query variations if initial results are insufficient
                        - Look for specific numbers and percentages
                        - Note the source credibility

                        Example search queries:
                        - "Nocca gnocchi NYC menu reviews Yelp"
                        - "gnocchi restaurant NYC Nolita competitor menu prices"
                        - "fast casual Italian restaurant average check size 2024"
                        - "menu engineering Cornell research PDF"
                        - "restaurant menu description words sales increase study"

                        # Required Output Structure

                        Your final report MUST include all these sections:

                        ```markdown
                        # Deep Research Report: [Restaurant Name] Menu Optimization

                        ## Research Summary
                        - Total searches conducted: [X]
                        - Sources analyzed: [X]
                        - Research duration: [X] phases completed

                        ---

                        ## Part 1: Current State Analysis

                        ### 1.1 Menu Inventory
                        [Complete list of all menu items with prices, organized by category]

                        ### 1.2 Pricing Analysis
                        - Price range: $X - $X
                        - Average item price: $X
                        - Price distribution analysis
                        - Comparison to market rates

                        ### 1.3 Menu Structure Assessment
                        - Number of categories: X
                        - Items per category: X-X
                        - Menu complexity score
                        - Navigation/decision fatigue analysis

                        ### 1.4 Customer Perception (from reviews)
                        - What customers love (with quotes)
                        - Common complaints or suggestions
                        - Most mentioned items
                        - Price perception

                        ---

                        ## Part 2: Competitive Landscape

                        ### 2.1 Direct Competitors Identified
                        [List 3-5 competitors with brief profile]

                        ### 2.2 Competitive Pricing Comparison
                        | Item Type | Target Restaurant | Competitor Avg | Market Position |
                        |-----------|------------------|----------------|-----------------|
                        | [Item] | $X | $X | Above/Below/At market |

                        ### 2.3 Competitive Advantages & Gaps
                        - What competitors do better
                        - What target restaurant does better
                        - Unexploited opportunities

                        ---

                        ## Part 3: Industry Context

                        ### 3.1 Market Trends
                        - [Trend 1 with data source]
                        - [Trend 2 with data source]
                        - [Trend 3 with data source]

                        ### 3.2 Industry Benchmarks
                        | Metric | Industry Average | Target Restaurant | Gap |
                        |--------|-----------------|-------------------|-----|
                        | Average check | $X | $X (est.) | +/-X% |
                        | Food cost % | X% | X% (est.) | +/-X% |

                        ### 3.3 Consumer Preferences
                        - Key findings from consumer research
                        - Relevance to target restaurant

                        ---

                        ## Part 4: Evidence-Based Recommendations

                        ### Priority 1: [Highest Impact Change]
                        **The Opportunity:** [What to change]

                        **Research Evidence:**
                        - [Study/source 1]: [Finding]
                        - [Study/source 2]: [Finding]
                        - [Competitor example]: [What they do]

                        **Specific Implementation:**
                        1. [Step 1]
                        2. [Step 2]
                        3. [Step 3]

                        **Expected Impact:**
                        - Based on [source], similar changes resulted in X% improvement
                        - Estimated revenue impact: $X - $X per month
                        - Time to see results: X weeks

                        **Cost & Effort:** [Free/Low/Medium] - [X hours to implement]

                        ---

                        ### Priority 2: [Second Highest Impact]
                        [Same detailed format]

                        ---

                        ### Priority 3: [Third Highest Impact]
                        [Same detailed format]

                        ---

                        ### Additional Recommendations (Lower Priority)
                        4. [Recommendation with brief evidence]
                        5. [Recommendation with brief evidence]
                        6. [Recommendation with brief evidence]

                        ---

                        ## Part 5: Implementation Roadmap

                        ### Week 1: Quick Wins
                        - [ ] [Task 1]
                        - [ ] [Task 2]
                        - [ ] [Task 3]

                        ### Week 2-4: Core Changes
                        - [ ] [Task 1]
                        - [ ] [Task 2]

                        ### Month 2-3: Strategic Initiatives
                        - [ ] [Task 1]
                        - [ ] [Task 2]

                        ---

                        ## Part 6: Expected Results Summary

                        | Metric | Current | Target (90 days) | Improvement |
                        |--------|---------|------------------|-------------|
                        | Average Check | $X | $X | +X% |
                        | Monthly Revenue | $X | $X | +$X |
                        | Profit Margin | X% | X% | +X% |

                        **Total Estimated Annual Revenue Increase: $X - $X**

                        ---

                        ## Research Sources & References

                        ### Primary Sources (Target Restaurant)
                        1. [Source](URL) - [What was gathered]

                        ### Competitor Sources
                        1. [Source](URL) - [What was gathered]

                        ### Industry & Academic Sources
                        1. [Source](URL) - [Key finding]

                        ### Case Studies Referenced
                        1. [Source](URL) - [Key finding]
                        ```

                        # Quality Standards

                        - Report must be at least 3000 words
                        - Must cite at least 15 different sources
                        - Every recommendation must reference specific research
                        - Include specific numbers and percentages wherever possible
                        - Cross-reference key findings across multiple sources

                        # Available Tools

                        - **tavily_search**: Your primary research tool. Use extensively with varied queries.
                          - Always use `search_depth: "advanced"` for comprehensive results
                          - Use `topic: "news"` for recent developments
                          - Use `topic: "general"` for broad research
                        - **write_todos**: Track your progress through the 6 research phases
                        - **write_file**: Save intermediate findings and final report
                        - **read_file**: Read any saved files

                        # Task Management

                        Use write_todos to create and track tasks for each research phase:
                        - Phase 1: Target Menu Deep Dive [IN_PROGRESS/COMPLETED]
                        - Phase 2: Competitor Analysis [PENDING/IN_PROGRESS/COMPLETED]
                        - Phase 3: Industry Data [PENDING/IN_PROGRESS/COMPLETED]
                        - Phase 4: Menu Psychology [PENDING/IN_PROGRESS/COMPLETED]
                        - Phase 5: Consumer Research [PENDING/IN_PROGRESS/COMPLETED]
                        - Phase 6: Case Studies [PENDING/IN_PROGRESS/COMPLETED]
                        - Final Report Writing [PENDING/IN_PROGRESS/COMPLETED]

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
                .maxTurn(200)
                .model("gpt-4.1")
                .compression(customCompression)
                .reflectionConfig(ReflectionConfig.withEvaluationCriteria(reflectionCriteria))
                .build();

        logger.info("setup agent: {}", agent);

        var researchRequest = """
                ## Deep Research Request: Restaurant Menu Optimization

                **Target Restaurant:**
                - Name: Nocca / Nocca Nolita
                - Brand website: https://www.nocca.co/
                - Online ordering: https://www.getsauce.com/order/nocca/menu/gnocchi-bowls-028e
                - Cuisine: Specialty gnocchi (Italian fast-casual)
                - Location: Nolita, NYC

                **Research Objectives:**
                1. Comprehensive analysis of current menu (items, pricing, structure)
                2. Competitive landscape in the Nolita/NYC fast-casual Italian market
                3. Industry benchmarks for similar restaurant concepts
                4. Evidence-based recommendations for revenue optimization

                **Key Questions to Answer Through Research:**
                1. How does Nocca's pricing compare to competitors in the area?
                2. What menu items are customers talking about in reviews?
                3. What are successful gnocchi/Italian fast-casual restaurants doing differently?
                4. What does research say about menu optimization for this type of concept?
                5. What specific changes could increase average check size by 15-20%?

                **Deliverable:**
                A comprehensive research report (3000+ words) with:
                - Complete menu analysis with pricing comparison
                - Competitive intelligence from at least 3 competitors
                - Industry data and benchmarks
                - Evidence-based recommendations with expected ROI
                - Implementation roadmap

                **Important:**
                This is a DEEP RESEARCH task. Take your time to:
                - Conduct at least 15-20 different searches
                - Gather data from multiple source types
                - Cross-verify key findings
                - Build a comprehensive evidence base before making recommendations
                """;

        agent.run(Strings.format("""
                Conduct comprehensive deep research on this restaurant and provide a detailed optimization report:

                {}

                CRITICAL REQUIREMENTS:
                1. Complete ALL 6 research phases
                2. Conduct at least 15-20 searches across different topics
                3. Cross-reference findings from multiple sources
                4. Final report must be 3000+ words with proper citations
                5. Save the final report to: {{workspace}}/deep_research_report.md

                Take your time - thorough research is more valuable than quick answers.
                """, researchRequest), ExecutionContext.builder().customVariable("workspace", "/Users/xander/Desktop/deep-research-output").build());

        logger.info(Strings.format("agent token cost: total={}, input={}, output={}",
                agent.getCurrentTokenUsage().getTotalTokens(),
                agent.getCurrentTokenUsage().getPromptTokens(),
                agent.getCurrentTokenUsage().getCompletionTokens()));
    }
}
