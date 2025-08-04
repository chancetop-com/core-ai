package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import ai.core.llm.LLMProviders;
import ai.core.mcp.server.McpServerToolLoader;
import ai.core.tool.ToolCall;
import ai.core.tool.function.Functions;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class CodingMcpService implements McpServerToolLoader {
    @Inject
    LLMProviders llmProviders;
    private final String coreNgWiki;

    public CodingMcpService(String coreNgWiki) {
        this.coreNgWiki = coreNgWiki;
    }

    @CoreAiMethod(name = "core-ng-coding-assist", description = "assist coding, e.g. write code, fix bug, refactor code, give coding best practices advice, etc.")
    public String assist(@CoreAiParameter(name = "query", description = "user's query", required = true) String query) {
        var agent = Agent.builder()
                .systemPrompt("""
                        # Goal:
                        You are a core-ng expert, proficient in developing Java web applications using core-ng.
                        When you receive a query, you should analyze the user's requirements and then tell the user how to implement the functionality with core-ng framework.
                        
                        # Instructions:
                        - Your response should include detailed information for each step, and when generating sample code, it should always include imports.
                        - When generating property definitions or table definitions of time related fields, prefer to use the ZonedDateTime type.
                        
                        # Core-ng Guide and Example wiki:
                        
                        """ + coreNgWiki)
                .llmProvider(llmProviders.getDefaultProvider())
                .build();
        return agent.run(query, null);
    }

    @Override
    public List<ToolCall> load() {
        return new ArrayList<>(Functions.from(this, "assist"));
    }
}
