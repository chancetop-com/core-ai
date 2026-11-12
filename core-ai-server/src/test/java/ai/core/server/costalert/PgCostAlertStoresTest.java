package ai.core.server.costalert;

import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgCostAlertStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("cost-alert-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgCostAlertRuleRow.class);
        database.view(PgCostAlertEventRow.class);
        createTables();
        return database;
    }

    private static void createTables() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE TABLE IF NOT EXISTS app.cost_alert_rules ("
                + "id text PRIMARY KEY, name text NOT NULL, enabled boolean NOT NULL, metric text NOT NULL, "
                + "scope text NOT NULL, scope_value text NOT NULL, threshold double precision NOT NULL, "
                + "created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.cost_alert_events ("
                + "id text PRIMARY KEY, rule_id text NOT NULL, rule_name text NOT NULL, date timestamptz NOT NULL, "
                + "scope text NOT NULL, scope_value text NOT NULL, metric text NOT NULL, "
                + "threshold double precision NOT NULL, actual_value double precision NOT NULL, status text NOT NULL, "
                + "created_at timestamptz NOT NULL, data jsonb NOT NULL, "
                + "CONSTRAINT cost_alert_events_unique UNIQUE (rule_id, scope, scope_value, date))");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize cost alert test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTables() {
        DATABASE.execute("DELETE FROM app.cost_alert_rules");
        DATABASE.execute("DELETE FROM app.cost_alert_events");
    }

    @Test
    void ruleCrudAndEventIdempotency() {
        var ruleStore = new PgCostAlertRuleStore();
        ruleStore.database = DATABASE;
        var eventStore = new PgCostAlertEventStore();
        eventStore.database = DATABASE;

        var rule = rule("rule-1");
        ruleStore.insert(rule);
        assertTrue(ruleStore.get("rule-1").isPresent());
        assertEquals(1, ruleStore.list().size());
        rule.name = "updated";
        ruleStore.replace(rule);
        assertEquals("updated", ruleStore.get("rule-1").orElseThrow().name);
        ruleStore.delete("rule-1");
        assertTrue(ruleStore.get("rule-1").isEmpty());

        ruleStore.insert(rule);
        var event = event("event-1", rule.id);
        assertTrue(eventStore.insertIfAbsent(event));
        var duplicate = event("event-2", rule.id);
        duplicate.date = event.date;
        assertFalse(eventStore.insertIfAbsent(duplicate));
        assertEquals(List.of("event-1"), eventStore.list(rule.id, null, null, 10).stream().map(e -> e.id).toList());
    }

    private CostAlertRule rule(String id) {
        var rule = new CostAlertRule();
        rule.id = id;
        rule.name = "rule";
        rule.enabled = Boolean.TRUE;
        rule.metric = CostAlertMetric.COST_USD;
        rule.scope = CostAlertScope.GLOBAL;
        rule.scopeValue = "";
        rule.threshold = 10.0;
        rule.targets = "[]";
        rule.createdAt = ZonedDateTime.now();
        rule.updatedAt = rule.createdAt;
        return rule;
    }

    private CostAlertEvent event(String id, String ruleId) {
        var event = new CostAlertEvent();
        event.id = id;
        event.ruleId = ruleId;
        event.ruleName = "rule";
        event.date = ZonedDateTime.now();
        event.scope = "GLOBAL";
        event.scopeValue = "";
        event.metric = "COST_USD";
        event.threshold = 10.0;
        event.actualValue = 12.0;
        event.detail = "{}";
        event.status = "sent";
        event.createdAt = ZonedDateTime.now();
        return event;
    }
}
