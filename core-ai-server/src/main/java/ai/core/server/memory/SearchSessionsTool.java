package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.server.session.SessionSearchHit;
import ai.core.server.session.SessionSearchQuery;
import ai.core.server.session.SessionSearchService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.List;
import java.util.Map;

/**
 * Searches the user's own past conversations with this agent by keyword and returns excerpts.
 *
 * <p>The counterpart of {@link SearchMemoryTool}: a memory is a distilled conclusion, a conversation is
 * the raw record behind it. When the details are needed — the command, the id, the exact wording — the
 * answer is in the history, not in the memory, and opening whole conversations would be unbounded, so
 * the search returns excerpts and a session handle to drill into.
 *
 * @author stephen
 */
public final class SearchSessionsTool extends ToolCall {
    static final String TOOL_NAME = "search_sessions";
    private static final String TOOL_DESC = """
            Search your past conversations with this user for something already discussed, when the details are not in
            your current context.

            Use it when the user refers to an earlier conversation ("the script we wrote", "last time", "that URL",
            "my usual setup") and search_memory only holds a distilled fact without the detail you need. Pass short
            keywords, not a sentence: names, ids, file names, commands, error text and identifiers match best, because
            the search is literal.

            The answer lists matching conversations with their title, time, session id and matching excerpts. Pass a
            returned `session_id` to look deeper inside that one conversation. Results are excerpts, never the whole
            conversation, and cover only this user's own conversations with you, excluding the current one.
            """;

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "query", "Keywords to look for, a few words at most; concrete terms beat full sentences").required(),
                ToolCallParameters.ParamSpec.of(String.class, "session_id", "Optional session id from a previous search; searches only inside that conversation and returns more of its excerpts")
                        .optional(),
                ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Optional number of conversations to return (default 5, max 20); with session_id it bounds excerpts instead")
                        .optional(),
                ToolCallParameters.ParamSpec.of(Integer.class, "window_days", "Optional how many days back to search (default 90, max 365)").optional()
        );
    }

    private final String agentId;
    private final SessionSearchService sessionSearchService;

    public SearchSessionsTool(String agentId, SessionSearchService sessionSearchService) {
        this.agentId = agentId;
        this.sessionSearchService = sessionSearchService;
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
        return ToolCallResult.failed("search_sessions requires ExecutionContext; direct execute is not supported");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var query = getStringValue(args, "query");
        if (query == null || query.isBlank()) {
            return ToolCallResult.failed("query is required; pass a few keywords to look for");
        }
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            return ToolCallResult.failed("search_sessions needs a user session; there is nothing to search without one");
        }
        var request = new SessionSearchQuery();
        request.agentId = agentId;
        request.userId = context.getUserId();
        request.currentSessionId = context.getSessionId();
        request.query = query;
        request.sessionId = getStringValue(args, "session_id");
        request.limit = intValue(args, "limit", SessionSearchService.DEFAULT_LIMIT);
        request.windowDays = intValue(args, "window_days", SessionSearchService.DEFAULT_WINDOW_DAYS);
        var hits = sessionSearchService.search(request);
        if (hits.isEmpty()) {
            return ToolCallResult.completed(noMatchText(request));
        }
        return ToolCallResult.completed(render(request, hits));
    }

    private String noMatchText(SessionSearchQuery request) {
        if (request.sessionId != null && !request.sessionId.isBlank()) {
            return "No matching messages in conversation " + request.sessionId + " within the last " + request.windowDays
                    + " days. Retry with different keywords or a larger window_days.";
        }
        return "No past conversation matched. Try fewer or different keywords — names, ids, file names, commands and error text"
                + " match better than full sentences — and raise window_days if the conversation is older than " + request.windowDays + " days.";
    }

    private String render(SessionSearchQuery request, List<SessionSearchHit> hits) {
        var total = hits.stream().mapToInt(hit -> hit.matchCount).sum();
        var sb = new StringBuilder(1024);
        if (request.sessionId == null || request.sessionId.isBlank()) {
            sb.append("Found ").append(hits.size()).append(hits.size() == 1 ? " conversation" : " conversations")
                    .append(" matching \"").append(request.query).append("\" (").append(total).append(" matching messages, last ")
                    .append(request.windowDays).append(" days):\n");
        } else {
            sb.append("Found ").append(total).append(total == 1 ? " matching message" : " matching messages")
                    .append(" in this conversation (\"").append(request.query).append("\", last ").append(request.windowDays).append(" days):\n");
        }
        for (var hit : hits) {
            sb.append('\n').append(hitLine(hit));
            for (var snippet : hit.snippets) {
                sb.append("\n  [").append(snippet.role).append(' ').append(snippet.createdAt).append("] ").append(snippet.text);
            }
            sb.append('\n');
        }
        sb.append("\nThese are excerpts, not whole conversations.");
        if (request.sessionId == null || request.sessionId.isBlank()) {
            sb.append(" Pass a session_id to read more of one conversation, or search again with different keywords.");
        }
        return sb.toString();
    }

    private String hitLine(SessionSearchHit hit) {
        var title = hit.title == null || hit.title.isBlank() ? "(untitled)" : hit.title;
        var more = hit.matchCount > hit.snippets.size()
                ? " (showing " + hit.snippets.size() + " of " + hit.matchCount + ")" : "";
        return title + "\n  session_id: " + hit.sessionId + " | last message " + hit.lastMessageAt + " | " + hit.matchCount
                + (hit.matchCount == 1 ? " match" : " matches") + more;
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
