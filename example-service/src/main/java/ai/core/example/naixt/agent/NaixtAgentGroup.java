package ai.core.example.naixt.agent;

import ai.core.agent.Agent;
import ai.core.agent.AgentGroup;
import ai.core.example.naixt.service.LanguageServerToolingService;
import ai.core.llm.LLMProvider;
import ai.core.persistence.PersistenceProvider;
import ai.core.tool.function.Functions;

import java.util.List;

/**
 * @author stephen
 */
public class NaixtAgentGroup {
    public static AgentGroup of(LLMProvider llmProvider, PersistenceProvider persistenceProvider, LanguageServerToolingService languageServerToolingService) {
        var requirementAgent = Agent.builder()
                .name("requirement-agent")
                .description("requirement-agent is an agent that provide user's requirement and analysis result.")
                .systemPrompt("You are an assistant that helps users write requirement.")
                .promptTemplate("")
                .llmProvider(llmProvider).build();
        var languageServerAgent = Agent.builder()
                .name("language-server-agent")
                .description("language-server-agent is an agent that provider information from language server, for example, workspace information, source code information and so on.")
                .systemPrompt("You are an assistant that helps users find information.")
                .promptTemplate("")
                .toolCalls(Functions.from(languageServerToolingService))
                .llmProvider(llmProvider).build();
        var codingAgent = Agent.builder()
                .name("coding-agent")
                .description("coding-agent is an agent that help user to write CoreNG based code.")
                .systemPrompt("""
                        You are an assistant that helps users write code.
                        You have a highly skilled software engineer with extensive experience in Java, TypeScript, JavaScript and HTML/CSS.
                        You have a strong understanding of CoreNG, CoreFE, CoreAI framework.
                        You have extensive knowledge in software development principles, design patterns, and best practices.
                        """)
                .promptTemplate("")
                .model("gpt-4o-2024-08-06-CoreNGCodingBeta")
                .llmProvider(llmProvider).build();
        var gitAgent = Agent.builder()
                .name("git-agent")
                .description("git-agent is an agent that help user to manage git repository.")
                .systemPrompt("You are an assistant that helps users manage git repository.")
                .promptTemplate("")
                .llmProvider(llmProvider).build();
        return AgentGroup.builder()
                .agents(List.of(requirementAgent, languageServerAgent, codingAgent, gitAgent))
                .name("naixt-agent-group")
                .description("naixt-agent-group is a group of agents that help user to write code.")
                .persistenceProvider(persistenceProvider)
                .llmProvider(llmProvider).build();
    }
}
