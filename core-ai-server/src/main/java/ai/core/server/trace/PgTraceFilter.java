package ai.core.server.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Builds the WHERE clause of a trace query. Keeps the clause list and its bound parameters together so
 * the store never concatenates user input.
 *
 * @author stephen
 */
class PgTraceFilter {
    static PgTraceFilter of(TraceQuery criteria) {
        var filter = new PgTraceFilter();
        filter.addIdFilter(criteria);
        filter.addAttributeFilters(criteria);
        filter.addScopeFilters(criteria);
        filter.addTimeFilters(criteria);
        return filter;
    }

    static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private final List<String> clauses = new ArrayList<>();
    private final List<Object> params = new ArrayList<>();

    void add(String clause, Object... values) {
        clauses.add(clause);
        params.addAll(List.of(values));
    }

    void addIn(String column, List<String> values) {
        if (values.isEmpty()) {
            clauses.add("1 = 0");
            return;
        }
        clauses.add(column + " IN (" + placeholders(values.size()) + ")");
        params.addAll(values);
    }

    String where() {
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    Object[] params() {
        return params.toArray();
    }

    List<Object> paramList() {
        return params;
    }

    private void addIdFilter(TraceQuery criteria) {
        if (hasText(criteria.exactId)) {
            var lower = criteria.exactId.toLowerCase(Locale.ROOT);
            add("(session_id = ? OR user_id = ? OR trace_id = ?)", lower, lower, lower);
            if (!lower.equals(criteria.exactId)) {
                add("(session_id = ? OR user_id = ? OR trace_id = ?)", criteria.exactId, criteria.exactId, criteria.exactId);
            }
        } else if (hasText(criteria.idPrefix)) {
            var prefix = criteria.idPrefix.toLowerCase(Locale.ROOT) + "%";
            add("(session_id LIKE ? OR trace_id LIKE ?)", prefix, prefix);
        }
    }

    private void addAttributeFilters(TraceQuery criteria) {
        if (hasText(criteria.type)) add("type = ?", criteria.type);
        if (hasText(criteria.source)) {
            // Legacy traces predate the source field; treat missing source as "chat"
            if ("chat".equals(criteria.source)) {
                add("(source = ? OR source IS NULL OR length(source) = 0)", "chat");
            } else {
                add("source = ?", criteria.source);
            }
        }
        if (hasText(criteria.agentName)) add("agent_name = ?", criteria.agentName);
        if (hasText(criteria.agentId)) add("agent_id = ?", criteria.agentId);
        if (criteria.agentIds != null) addIn("agent_id", criteria.agentIds);
        if (hasText(criteria.model)) add("model = ?", criteria.model);
        if (hasText(criteria.status)) add("status = ?", criteria.status);
        if (criteria.agentIdPresent) {
            add("agent_id IS NOT NULL");
            add("length(agent_id) > 0");
        }
        if (hasText(criteria.sessionId)) add("session_id = ?", criteria.sessionId);
        if (criteria.sessionIds != null) addIn("session_id", criteria.sessionIds);
        if (criteria.traceIds != null) addIn("trace_id", criteria.traceIds);
        if (criteria.anySessionIds != null || criteria.anyTraceIds != null) {
            var or = new ArrayList<String>();
            var values = new ArrayList<Object>();
            if (criteria.anySessionIds != null) {
                or.add("session_id IN (" + placeholders(criteria.anySessionIds.size()) + ")");
                values.addAll(criteria.anySessionIds);
            }
            if (criteria.anyTraceIds != null) {
                or.add("trace_id IN (" + placeholders(criteria.anyTraceIds.size()) + ")");
                values.addAll(criteria.anyTraceIds);
            }
            add("(" + String.join(" OR ", or) + ")", values.toArray());
        }
        if (criteria.matchNothing) add("1 = 0");
    }

    private void addScopeFilters(TraceQuery criteria) {
        if (hasText(criteria.userId)) add("user_id = ?", criteria.userId);
        else if (criteria.userIds != null) addIn("user_id", criteria.userIds);
        if (criteria.extraUserIds != null) addIn("user_id", criteria.extraUserIds);
    }

    private void addTimeFilters(TraceQuery criteria) {
        if (criteria.createdFrom != null) add("created_at >= ?", criteria.createdFrom);
        if (criteria.createdTo != null) add("created_at <= ?", criteria.createdTo);
        if (criteria.startFrom != null) add("started_at >= ?", criteria.startFrom);
        if (criteria.startAfter != null) add("started_at > ?", criteria.startAfter);
        if (criteria.startTo != null) add("started_at <= ?", criteria.startTo);
        if (criteria.startBefore != null) add("started_at < ?", criteria.startBefore);
    }
}
