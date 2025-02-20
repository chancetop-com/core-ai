package ai.core.example.naixt.service;

import ai.core.agent.Agent;
import ai.core.example.api.ChatResponse;
import ai.core.example.api.naixt.NaixtChatRequest;
import ai.core.llm.providers.LiteLLMProvider;
import core.framework.inject.Inject;

import java.util.HashMap;

/**
 * @author stephen
 */
public class NaixtService {
    @Inject
    LiteLLMProvider liteLLMProvider;

    public ChatResponse chat(NaixtChatRequest request) {
        var agent = Agent.builder()
                .systemPrompt("""
                You are an assistant that helps users write code.
                You have a highly skilled software engineer with extensive experience in Java, TypeScript, JavaScript and HTML/CSS.
                You have extensive knowledge in software development principles, design patterns, and best practices.
                """)
                .promptTemplate("""
                User current editor file content:
                {{current_file_content}}
                User current editor position:
                line: {{current_line_number}}, column: {{current_column_number}}
                User's query:
                """)
                .llmProvider(liteLLMProvider).build();
        var context = new HashMap<String, Object>();
        context.put("current_file_content", IdeUtils.getFileContent(request.currentFilePath));
        context.put("current_line_number", request.currentLineNumber);
        context.put("current_column_number", request.currentColumnNumber);
        return ChatResponse.of(agent.run(request.query, context));
    }
}
