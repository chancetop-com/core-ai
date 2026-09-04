package ai.core.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared keyword matching and scoring for tool discovery search. Used by the hub
 * catalog search ({@code GET /api/mcp-hub/tools}) and the agent-side
 * {@code activate_tools} tool, so both keep the same tokenization and field weights.
 * <p>
 * Callers choose the keep rule from {@link Match}: catalog search keeps tools where
 * {@code allTokensHit} (every token must hit some field), activation search keeps
 * tools where {@code anyTokenHit} (recall-first for free-text queries) and ranks by
 * score.
 * <p>
 * Weights are layered: a token hitting the registered server name is a "brand-level"
 * signal and outweighs tool-name hits, because a server name identifies a whole tool
 * family while tool names are only meaningful inside their server.
 *
 * @author stephen
 */
public final class ToolSearchScorer {
    public static final int NAME_EQUALS_QUERY = 100;
    public static final int SERVER_NAME_EQUALS_TOKEN = 100;
    public static final int SERVER_NAME_CONTAINS_TOKEN = 50;
    public static final int TOOL_NAME_CONTAINS_TOKEN = 10;
    public static final int DESCRIPTION_CONTAINS_TOKEN = 2;

    /** Lowercases and splits on whitespace, dropping blank parts. */
    public static List<String> tokenize(String query) {
        var tokens = new ArrayList<String>();
        if (query == null) return tokens;
        for (var part : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!part.isBlank()) tokens.add(part);
        }
        return tokens;
    }

    /**
     * Scores one tool against a query. {@code serverName} is optional (agent tools
     * have none) and {@code description} may be null.
     */
    public static Match match(String name, String description, String serverName, String query) {
        var tokens = tokenize(query);
        if (tokens.isEmpty()) return new Match(0, true, false);
        var lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        var lowerDescription = description == null ? "" : description.toLowerCase(Locale.ROOT);
        var lowerServer = serverName == null ? null : serverName.toLowerCase(Locale.ROOT);
        int score = 0;
        if (name != null && name.equalsIgnoreCase(query)) score += NAME_EQUALS_QUERY;
        boolean anyHit = false;
        boolean allHit = true;
        for (var token : tokens) {
            boolean hit = false;
            if (lowerServer != null && lowerServer.contains(token)) {
                score += lowerServer.equals(token) ? SERVER_NAME_EQUALS_TOKEN : SERVER_NAME_CONTAINS_TOKEN;
                hit = true;
            }
            if (lowerName.contains(token)) {
                score += TOOL_NAME_CONTAINS_TOKEN;
                hit = true;
            }
            if (!lowerDescription.isEmpty() && lowerDescription.contains(token)) {
                score += DESCRIPTION_CONTAINS_TOKEN;
                hit = true;
            }
            if (hit) {
                anyHit = true;
            } else {
                allHit = false;
            }
        }
        return new Match(score, allHit, anyHit);
    }

    /** Brand-level score of a registered server name against the query tokens. */
    public static int serverNameScore(String serverName, List<String> tokens) {
        if (serverName == null || tokens.isEmpty()) return 0;
        var lower = serverName.toLowerCase(Locale.ROOT);
        int score = 0;
        for (var token : tokens) {
            if (lower.contains(token)) {
                score += lower.equals(token) ? SERVER_NAME_EQUALS_TOKEN : SERVER_NAME_CONTAINS_TOKEN;
            }
        }
        return score;
    }

    public record Match(int score, boolean allTokensHit, boolean anyTokenHit) {
    }
}
