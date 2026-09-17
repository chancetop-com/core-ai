package ai.core.server.session;

/**
 * Input of {@link SessionSearchService#search}.
 *
 * @author stephen
 */
public class SessionSearchQuery {
    public String agentId;
    public String userId;
    // excluded from results: the conversation the caller is currently in
    public String currentSessionId;
    public String query;
    // optional drill-down: search only inside this conversation
    public String sessionId;
    public int limit = SessionSearchService.DEFAULT_LIMIT;
    public int windowDays = SessionSearchService.DEFAULT_WINDOW_DAYS;
}
