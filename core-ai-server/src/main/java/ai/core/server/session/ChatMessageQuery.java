package ai.core.server.session;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Storage-neutral filter for chat message lookups.
 *
 * <p>{@link #anyScope} expresses the memory-consolidation query: match any trace id or any
 * (session, time window) pair. Without it the same fields are ANDed.
 *
 * @author stephen
 */
public class ChatMessageQuery {
    public String sessionId;
    public List<String> sessionIds;
    public List<String> traceIds;
    public List<SessionWindow> sessionWindows;
    public boolean anyScope;

    /** OR of case-insensitive substring matches; only used by history search. */
    public List<String> contentTerms;

    public String role;

    /** inclusive */
    public ZonedDateTime from;

    /** inclusive */
    public ZonedDateTime to;

    /** seq | created_at */
    public String sortField = "seq";
    public boolean ascending = true;
    public int offset;
    public int limit = 1000;

    public static class SessionWindow {
        public String sessionId;
        public ZonedDateTime from;
        public ZonedDateTime to;
    }
}
