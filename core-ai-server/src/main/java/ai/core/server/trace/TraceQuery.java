package ai.core.server.trace;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Typed trace lookup criteria. Both stores translate this to their own query dialect, so callers do
 * not need to know whether the trace lives in Mongo or PostgreSQL.
 *
 * @author stephen
 */
public class TraceQuery {
    // exact id search: a pasted full trace/session/user id, matched case-insensitively
    public String exactId;
    // anchored prefix on trace_id / session_id, used by the UI's short session prefix
    public String idPrefix;
    public String type;
    public String source;
    public String agentName;
    public String agentId;
    public String model;
    public String status;
    public String sessionId;
    // exact trace ids, used when a caller already holds a batch of them
    public List<String> traceIds;
    // only traces that carry an agent id (non-null and non-blank)
    public boolean agentIdPresent;
    public String userId;
    // null = not scoped, empty = match nothing
    public List<String> userIds;
    // additional user ids that must also match, used when a text search expands to several accounts
    public List<String> extraUserIds;
    public ZonedDateTime startFrom;
    public ZonedDateTime startTo;
    // exclusive lower bound
    public ZonedDateTime startAfter;
    // exclusive upper bound, used by the archive job so the boundary row is never archived twice
    public ZonedDateTime startBefore;
    public boolean ascending;
    // null means no offset; the archive delete loop relies on that to page by started_at only
    public Integer skip;
    // null means no limit
    public Integer limit;
}
