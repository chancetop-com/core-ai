package ai.core.server.agent;

import ai.core.api.server.agent.GenerateAgentDraftResponse;
import ai.core.api.server.tool.ToolRefView;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.ToolRef;
import ai.core.session.InProcessAgentSession;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
public class AgentDraftGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentDraftGenerator.class);
    private static final int MAX_CONVERSATION_LENGTH = 8000;

    private static final String SYSTEM_PROMPT = """
            You are an agent configuration generator. Analyze the conversation history between a user and an AI assistant, then generate an agent configuration that can replicate this workflow automatically.

            You must respond in valid JSON with the following fields:
            - name: a concise name for this agent (under 50 chars)
            - description: what this agent does (1-2 sentences)
            - system_prompt: the system prompt that instructs the agent to perform this workflow. Include specific steps, data sources, output format, and any domain knowledge established in the conversation. Be detailed enough that the agent can work independently without user guidance.
            - input_template: the input text for each run. Use {{variable}} syntax for dynamic parts, e.g. {{today}}, {{yesterday}}. If the task is date-sensitive, include date variables.

            Guidelines:
            - The system_prompt should capture the WORKFLOW, not the conversation itself
            - If the user discussed analyzing logs, writing reports, etc., encode the specific analysis steps and output requirements
            - The input_template should be what triggers each run, not a copy of the original user request
            - Use the same language as the conversation for name and description
            """;

    @Inject
    LLMProviders llmProviders;

    public GenerateAgentDraftResponse generate(InProcessAgentSession session) {
        var agent = session.agent();
        var conversationSummary = buildConversationSummary(agent.getMessages());

        LOGGER.info("generating agent draft from session, sessionId={}, conversationLength={}", session.id(), conversationSummary.length());

        var userPrompt = "Conversation history:\n\n" + conversationSummary;

        var draft = llmProviders.getProvider().completionFormat(
                SYSTEM_PROMPT,
                userPrompt,
                agent.getModel(),
                GenerateAgentDraftResponse.class
        );

        draft.model = agent.getModel();
        draft.temperature = agent.getTemperature();
        draft.maxTurns = 20;
        draft.tools = agent.getToolCalls().stream().map(tc -> {
            var view = new ToolRefView();
            var ref = ToolRef.fromLegacyToolId(tc.getName());
            view.id = ref.id;
            view.type = ref.type != null ? ref.type.name() : null;
            view.source = ref.source;
            return view;
        }).toList();

        return draft;
    }

    private String buildConversationSummary(List<Message> messages) {
        var sb = new StringBuilder();
        for (var message : messages) {
            if (message.role == RoleType.SYSTEM) continue;
            if (message.role == RoleType.TOOL) continue;

            var role = message.role == RoleType.USER ? "User" : "Assistant";
            var text = message.getTextContent();
            if (text == null || text.isBlank()) continue;

            // skip tool call only messages
            if (message.role == RoleType.ASSISTANT && message.toolCalls != null && !message.toolCalls.isEmpty() && text.isBlank()) continue;

            sb.append(role).append(": ").append(text).append("\n\n");
        }
        var summary = sb.toString();
        if (summary.length() > MAX_CONVERSATION_LENGTH) {
            summary = summary.substring(summary.length() - MAX_CONVERSATION_LENGTH);
            var newlineIndex = summary.indexOf('\n');
            if (newlineIndex > 0) {
                summary = summary.substring(newlineIndex + 1);
            }
        }
        return summary;
    }
}
