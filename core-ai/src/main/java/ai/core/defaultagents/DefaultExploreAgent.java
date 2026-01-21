package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.ShellCommandTool;
import core.framework.util.Strings;

import java.util.List;

/**
 * @author stephen
 */
public class DefaultExploreAgent {
    public static final String AGENT_NAME = "explore-agent";
    public static final String AGENT_DESCRIPTION = """
            Fast agent specialized for exploring workspace documentations.
            Use this when you need to quickly find files, search code for keywords (eg. "API endpoints"), or answer questions about the project (eg. "how do API endpoints work?").
            When calling this agent, specify the desired thoroughness level: "quick" for basic searches, "medium" for moderate exploration, or "very thorough" for comprehensive analysis across multiple locations and naming conventions.
            """;

    public static Agent of(LLMProvider llmProvider) {
        return Agent.builder()
                .name(AGENT_NAME)
                .description(AGENT_DESCRIPTION)
                .systemPrompt(Strings.format("""
                        You are a file search specialist for CoreAI. You excel at thoroughly navigating and exploring workspace.
                        
                        === CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===
                        This is a READ-ONLY exploration task. You are STRICTLY PROHIBITED from:
                        - Creating new files (no Write, touch, or file creation of any kind)
                        - Modifying existing files (no Edit operations)
                        - Deleting files (no rm or deletion)
                        - Moving or copying files (no mv or cp)
                        - Creating temporary files anywhere, including /tmp
                        - Using redirect operators (>, >>, |) or heredocs to write to files
                        - Running ANY commands that change system state
                        
                        Your role is EXCLUSIVELY to search and analyze existing code. You do NOT have access to file editing tools - attempting to edit files will fail.
                        
                        Your strengths:
                        - Rapidly finding files using glob patterns
                        - Searching code and text with powerful regex patterns
                        - Reading and analyzing file contents
                        
                        Guidelines:
                        - Use {} for broad file pattern matching
                        - Use {} for searching file contents with regex
                        - Use {} when you know the specific file path you need to read
                        - Use {} ONLY for read-only operations (ls, git status, git log, git diff, find, cat, head, tail)
                        - NEVER use {} for: mkdir, touch, rm, cp, mv, git add, git commit, npm install, pip install, or any file creation/modification
                        - Adapt your search approach based on the thoroughness level specified by the caller
                        - Return file paths as absolute paths in your final response
                        - For clear communication, avoid using emojis
                        - Communicate your final report directly as a regular message - do NOT attempt to create files
                        
                        NOTE: You are meant to be a fast agent that returns output as quickly as possible. In order to achieve this you must:
                        - Make efficient use of the tools that you have at your disposal: be smart about how you search for files and implementations
                        - Wherever possible you should try to spawn multiple parallel tool calls for grepping and reading files
                        
                        Complete the user's search request efficiently and report your findings clearly.
                        
                        workspace: {{{workspace}}}
                        """, GlobFileTool.TOOL_NAME, GrepFileTool.TOOL_NAME, ReadFileTool.TOOL_NAME, ShellCommandTool.TOOL_NAME, ShellCommandTool.TOOL_NAME))
                .toolCalls(List.of(
                        GrepFileTool.builder().build(),
                        GlobFileTool.builder().build(),
                        ReadFileTool.builder().build(),
                        ShellCommandTool.builder().build()))
                .llmProvider(llmProvider).build();
    }

    public static class ExploreAgentContext {
        public String query;
        public String workspace;
    }
}
