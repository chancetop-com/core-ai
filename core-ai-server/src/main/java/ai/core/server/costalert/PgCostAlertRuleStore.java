package ai.core.server.costalert;

import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgCostAlertRuleStore implements CostAlertRuleStore {
    private static final String TABLE = "app.cost_alert_rules";
    private static final String SELECT = "SELECT id, name, enabled, metric, scope, scope_value, threshold, "
        + "created_at, updated_at, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, name, enabled, metric, scope, scope_value, "
        + "threshold, created_at, updated_at, data) ";

    private static CostAlertRule toRule(PgCostAlertRuleRow row) {
        var rule = JsonUtil.fromJson(CostAlertRule.class, row.data);
        rule.id = row.id;
        rule.name = row.name;
        rule.enabled = row.enabled;
        rule.metric = row.metric != null ? CostAlertMetric.valueOf(row.metric) : null;
        rule.scope = row.scope != null ? CostAlertScope.valueOf(row.scope) : null;
        rule.scopeValue = row.scopeValue;
        rule.threshold = row.threshold;
        rule.createdAt = row.createdAt;
        rule.updatedAt = row.updatedAt;
        return rule;
    }

    @Inject
    public Database database;

    @Override
    public Optional<CostAlertRule> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgCostAlertRuleRow.class, id)
            .map(PgCostAlertRuleStore::toRule);
    }

    @Override
    public List<CostAlertRule> list() {
        return database.select(SELECT + " ORDER BY created_at DESC, id ASC", PgCostAlertRuleRow.class)
            .stream().map(PgCostAlertRuleStore::toRule).toList();
    }

    @Override
    public void insert(CostAlertRule rule) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(rule));
    }

    @Override
    public boolean insertIfAbsent(CostAlertRule rule) {
        return database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
            params(rule)) > 0;
    }

    @Override
    public void replace(CostAlertRule rule) {
        database.execute("UPDATE " + TABLE + " SET name = ?, enabled = ?, metric = ?, scope = ?, scope_value = ?, "
                + "threshold = ?, created_at = ?, updated_at = ?, data = ?::jsonb WHERE id = ?",
            rule.name, rule.enabled, rule.metric != null ? rule.metric.name() : null,
            rule.scope != null ? rule.scope.name() : null, rule.scopeValue, rule.threshold,
            rule.createdAt, rule.updatedAt, JsonSanitizer.json(JsonUtil.toJson(rule)), rule.id);
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    private Object[] params(CostAlertRule rule) {
        return new Object[]{
            rule.id,
            rule.name,
            rule.enabled,
            rule.metric != null ? rule.metric.name() : null,
            rule.scope != null ? rule.scope.name() : null,
            rule.scopeValue,
            rule.threshold,
            rule.createdAt,
            rule.updatedAt,
            JsonSanitizer.json(JsonUtil.toJson(rule))
        };
    }
}

