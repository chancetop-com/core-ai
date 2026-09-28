package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.server.session.SessionReadService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.List;
import java.util.Map;

/**
 * Opens one of the user's own past conversations and returns its tail.
 *
 * <p>The companion of {@link SearchSessionsTool}: a search needs a keyword, and a user answering a
 * notification has none — they were told about a conversation, not about a word in it. With the
 * session id from that notice, this reads what the conversation was doing without asking them to
 * repeat it.
 *
 * @author stephen
 */
public final class ReadSessionTool extends ToolCall {
    static final String TOOL_NAME = "read_session";
    private static final String TOOL_DESC = """
            Read the last messages of one of the user's own past conversations, identified by its session id.

            Use it when the user's message points at something you did elsewhere — most often when they were
            sent a notice on this channel ("session finished") and are now answering it, so their instruction has
            no matching context in this conversation. The notice, a previous search_sessions result, or an earlier
            message carries the session id; do not guess one.

            You may read any conversation of this user, including ones held by another agent. What you cannot do
            is act with that agent's tools: if the request needs one you do not have, say so instead of pretending.
            If the reply could refer to more than one conversation, or the target of an action that cannot be undone
            is not clear, ask — never guess.
            """;

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "session_id", "Session id of the conversation to read").required(),
                ToolCallParameters.ParamSpec.of(Integer.class, "tail", "Optional how many of the latest messages to read (default 6, max 20)").optional()
        );
    }

    private final SessionReadService sessionReadService;

    public ReadSessionTool(SessionReadService sessionReadService) {
        this.sessionReadService = sessionReadService;
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
        return ToolCallResult.failed("read_session requires ExecutionContext; direct execute is not supported");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var sessionId = getStringValue(args, "session_id");
        if (sessionId == null || sessionId.isBlank()) {
            return ToolCallResult.failed("session_id is required; it is the id a notice or a search result carried");
        }
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            return ToolCallResult.failed("read_session needs a user session; there is nothing to read without one");
        }
        var read = sessionReadService.read(context.getUserId(), sessionId, intValue(args, "tail", SessionReadService.DEFAULT_TAIL));
        if (read == null) {
            return ToolCallResult.failed("No such conversation belongs to this user: " + sessionId
                    + ". Only conversations of your own are readable — check the id from the notice or search result.");
        }
        return ToolCallResult.completed(render(sessionId, read));
    }

    private String render(String sessionId, SessionReadService.SessionRead read) {
        var text = new StringBuilder(2048);
        text.append(read.title == null || read.title.isBlank() ? "(untitled conversation)" : read.title)
                .append("\nsession_id: ").append(sessionId);
        if (read.agentId != null) text.append(" | agent: ").append(read.agentId);
        if (read.lastMessageAt != null) text.append(" | last message: ").append(read.lastMessageAt);
        if (read.messages.isEmpty()) {
            text.append("\n\nThis conversation has no messages yet.");
            return text.toString();
        }
        text.append('\n');
        for (var line : read.messages) {
            text.append("\n[").append(line.role).append(line.at != null ? " " + line.at : "").append("] ")
                    .append(line.text == null || line.text.isBlank() ? "(no text)" : line.text);
            if (line.tools != null && !line.tools.isEmpty()) {
                text.append("\n  tools: ").append(String.join(", ", line.tools));
            }
        }
        if (!read.artifacts.isEmpty()) {
            text.append("\n\nFiles produced by this conversation: ").append(String.join(", ", read.artifacts));
        }
        if (read.moreMessages) {
            text.append("\n\n(These are the latest messages; earlier ones are not shown.)");
        }
        return text.toString();
    }

    private int intValue(Map<String, Object> args, String key, int fallback) {
        var value = args.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
