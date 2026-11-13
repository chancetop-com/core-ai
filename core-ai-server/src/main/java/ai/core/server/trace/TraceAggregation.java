package ai.core.server.trace;

/**
 * A grouped trace rollup: which dimension to group by, which optional first-value to carry, and the
 * filters in {@link #query}. The stores translate it into their own aggregation dialect.
 *
 * @author stephen
 */
public class TraceAggregation {
    public TraceQuery query;
    public Group group = Group.NONE;
    public FirstField firstField;
    // sort by started_at descending before grouping, so the first value is the latest one
    public boolean latestFirst;
    // 0 means no limit
    public int limit;

    public enum Group {
        NONE, DAY_BY_CREATED, SESSION_ID, TRACE_ID, AGENT_ID
    }

    public enum FirstField {
        TRACE_ID, AGENT_NAME
    }
}
