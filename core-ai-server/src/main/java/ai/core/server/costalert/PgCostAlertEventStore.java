package ai.core.server.costalert;

import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class PgCostAlertEventStore implements CostAlertEventStore {
    private static final String TABLE = "app.cost_alert_events";
    private static final String SELECT = "SELECT id, rule_id, rule_name, date, scope, scope_value, metric, threshold, "
        + "actual_value, status, created_at, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, rule_id, rule_name, date, scope, scope_value, "
        + "metric, threshold, actual_value, status, created_at, data) ";

    private static CostAlertEvent toEvent(PgCostAlertEventRow row) {
        var event = JsonUtil.fromJson(CostAlertEvent.class, row.data);
        event.id = row.id;
        event.ruleId = row.ruleId;
        event.ruleName = row.ruleName;
        event.date = row.date;
        event.scope = row.scope;
        event.scopeValue = row.scopeValue;
        event.metric = row.metric;
        event.threshold = row.threshold;
        event.actualValue = row.actualValue;
        event.status = row.status;
        event.createdAt = row.createdAt;
        return event;
    }

    @Inject
    public Database database;

    @Override
    public boolean insertIfAbsent(CostAlertEvent event) {
        return database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) "
            + "ON CONFLICT (rule_id, scope, scope_value, date) DO NOTHING", params(event)) > 0;
    }

    @Override
    public List<CostAlertEvent> list(String ruleId, ZonedDateTime from, ZonedDateTime to, int limit) {
        var sql = new StringBuilder(512).append(SELECT);
        var where = new ArrayList<String>();
        var params = new ArrayList<>();
        if (ruleId != null && !ruleId.isBlank()) {
            where.add("rule_id = ?");
            params.add(ruleId);
        }
        if (from != null) {
            where.add("date >= ?");
            params.add(from);
        }
        if (to != null) {
            where.add("date < ?");
            params.add(to);
        }
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(limit);
        return database.select(sql.toString(), PgCostAlertEventRow.class, params.toArray())
            .stream().map(PgCostAlertEventStore::toEvent).toList();
    }

    private Object[] params(CostAlertEvent event) {
        return new Object[]{
            event.id,
            event.ruleId,
            event.ruleName,
            event.date,
            event.scope,
            event.scopeValue,
            event.metric,
            event.threshold,
            event.actualValue,
            event.status,
            event.createdAt,
            JsonSanitizer.json(JsonUtil.toJson(event))
        };
    }
}

