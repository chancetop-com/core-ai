package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.List;

/**
 * Explicit "remember this" tool: the main agent stores one self-contained statement as Layer 1 knowledge when
 * the user asks for it ("记住…", "remember…").
 *
 * <p>The consolidation job distils whole sessions after the fact; this writes exactly what the user asked to
 * keep, in the same turn, as knowledge the agent may treat as SOP-safe. The row is written to the agent's own
 * memory store, so it shows up in later sessions, never in the current one.
 *
 * @author Xander
 */
public final class ExtractMemoryNowTool extends ToolCall {
    static final String TOOL_NAME = "extract_memory_now";
    private static final int MAX_FOCUS_LENGTH = 1000;
    private static final String TOOL_DESC = """
            Store something in your long-term memory so later sessions know it.

            Call this immediately when the user explicitly asks you to remember something — "记住…",
            "remember…", "don't forget…" — before answering or calling any other tool.

            - `focus`: the fact, preference or rule as ONE self-contained statement, for example
              "The user's production tenant is acme-prod and deployments happen on Thursdays".
            - `type`: USER_PREFERENCE for how the user wants things done, DOMAIN_KNOWLEDGE for stable
              facts about their world, GOTCHA for pitfalls to avoid. Defaults to DOMAIN_KNOWLEDGE.

            Do not use this tool for questions, for instructions that only apply to the current
            conversation, or for facts that are already in the conversation.
            """;

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "focus", "The fact, preference or rule to remember, written as one self-contained statement").required(),
                ToolCallParameters.ParamSpec.of(String.class, "type", "Memory type: 'USER_PREFERENCE' (how the user wants things done), 'DOMAIN_KNOWLEDGE' (default, stable facts), 'GOTCHA' (pitfalls to avoid)")
                        .optional()
                        .enums(KnowledgeType.names())
        );
    }

    private final String agentId;
    private final AgentMemoryService agentMemoryService;

    public ExtractMemoryNowTool(String agentId, AgentMemoryService agentMemoryService) {
        this.agentId = agentId;
        this.agentMemoryService = agentMemoryService;
        setName(TOOL_NAME);
        setDescription(TOOL_DESC);
        setParameters(parameters());
        setNeedAuth(Boolean.FALSE);
        setDirectReturn(Boolean.FALSE);
        setLlmVisible(Boolean.TRUE);
        setDiscoverable(Boolean.FALSE);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("extract_memory_now requires ExecutionContext; direct execute is not supported");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var focus = getStringValue(args, "focus");
        if (focus == null || focus.isBlank()) {
            return ToolCallResult.failed("focus is required: pass the fact, preference or rule as one self-contained statement");
        }
        if (focus.length() > MAX_FOCUS_LENGTH) {
            return ToolCallResult.failed("focus is too long (" + focus.length() + " chars, max " + MAX_FOCUS_LENGTH
                    + "): compress it into one self-contained statement");
        }
        var type = KnowledgeType.resolve(getStringValue(args, "type"));
        var result = agentMemoryService.rememberKnowledge(agentId, focus, type.name());
        if (result.status() == RememberResult.Status.ALREADY_EXISTS) {
            return ToolCallResult.completed("Already remembered, nothing changed (" + type.name() + "): " + result.memory().content);
        }
        return ToolCallResult.completed("Remembered as " + type.name()
                + ", available from your next session: " + result.memory().content);
    }
}
