package ai.core.example.naixt.agent;

import ai.core.agent.Agent;
import ai.core.agent.AgentGroup;
import ai.core.example.naixt.service.LanguageServerToolingService;
import ai.core.llm.LLMProvider;
import ai.core.mcp.client.MCPClientService;
import ai.core.mcp.client.MCPServerConfig;
import ai.core.persistence.PersistenceProvider;
import ai.core.tool.function.Functions;
import ai.core.tool.mcp.MCPToolCalls;

import java.util.List;

/**
 * @author stephen
 */
public class NaixtAgentGroup {
    public static AgentGroup of(LLMProvider llmProvider, PersistenceProvider persistenceProvider, LanguageServerToolingService languageServerToolingService) {
        var requirementAgent = Agent.builder()
                .name("requirement-agent")
                .description("requirement-agent is an agent that provide user's requirement and analysis result.")
                .systemPrompt("""
                        As a Requirements Analyst in software development, your task is to interpret and translate visual prototypes or textual Product Requirement Documents (PRD) into clear, actionable instructions for an AI Coding Agent.
                        Your output should meticulously detail functional requirements, user interactions, data flows, and any other pertinent information needed for the AI to generate code that aligns with the project's vision.
                        Please follow these guidelines for your output:
                        - Project Overview: Provide a concise summary of the project goals and objectives.
                        - Functional Specifications: List all functionalities required by the application, specifying inputs, processes, and outputs for each.
                        - User Interactions: Describe the intended user interactions with the system, including use cases and user interface flow.
                        - Data Models: Outline the structure of the data involved, including entities, attributes, and relationships.
                        - API Specifications: If applicable, provide details on API endpoints, request/response formats, and interaction protocols.
                        - Non-functional Requirements: Include performance criteria, security measures, and compliance standards that must be adhered to.
                        - Assumptions & Dependencies: Note any assumptions made during analysis and external dependencies that could affect implementation.
                        """)
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
                .toolCalls(MCPToolCalls.from(new MCPClientService(new MCPServerConfig("localhost", 8080))))
                .llmProvider(llmProvider).build();
        return AgentGroup.builder()
                .agents(List.of(requirementAgent, languageServerAgent, codingAgent, gitAgent))
                .name("naixt-agent-group")
                .description("naixt-agent-group is a group of agents that help user to write code.")
                .persistenceProvider(persistenceProvider)
                .llmProvider(llmProvider).build();
    }
}
