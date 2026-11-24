package ai.core.server.quality;

import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.server.quality.domain.AgentPublicationRecord;
import ai.core.server.quality.domain.AgentQualityPolicyHead;
import ai.core.server.quality.domain.AgentQualityPolicyRevision;
import ai.core.server.quality.domain.AgentQualityRule;
import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityGateMode;
import ai.core.server.quality.domain.QualityRunStatus;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JSONB-document implementation. Filtering only touches the fields the gate actually queries, so the
 * row payload stays flexible without becoming an untyped blob in application code.
 *
 * @author core-ai
 */
public class PgAgentQualityStore implements AgentQualityStore {
    private static final String RULES = "app.agent_quality_rules";
    private static final String REVISIONS = "app.agent_quality_rule_revisions";
    private static final String POLICIES = "app.agent_quality_policy_revisions";
    private static final String HEADS = "app.agent_quality_policy_heads";
    private static final String RUNS = "app.agent_quality_runs";
    private static final String PUBLICATIONS = "app.agent_publication_records";

    @Inject
    public Database database;

    private <T> PgDocumentStore<T> store(String table, Class<T> type, java.util.function.Function<T, String> id) {
        return new PgDocumentStore<>(database, table, type, id);
    }

    private PgDocumentStore<AgentQualityRule> rules() {
        return store(RULES, AgentQualityRule.class, rule -> rule.id);
    }

    private PgDocumentStore<AgentQualityRuleRevision> revisions() {
        return store(REVISIONS, AgentQualityRuleRevision.class, revision -> revision.id);
    }

    private PgDocumentStore<AgentQualityPolicyRevision> policies() {
        return store(POLICIES, AgentQualityPolicyRevision.class, policy -> policy.id);
    }

    private PgDocumentStore<AgentQualityPolicyHead> heads() {
        return store(HEADS, AgentQualityPolicyHead.class, head -> head.id);
    }

    private PgDocumentStore<AgentQualityRun> runs() {
        return store(RUNS, AgentQualityRun.class, run -> run.id);
    }

    private PgDocumentStore<AgentPublicationRecord> publications() {
        return store(PUBLICATIONS, AgentPublicationRecord.class, record -> record.id);
    }

    @Override
    public Optional<AgentQualityRule> getRule(String id) {
        return rules().get(id);
    }

    @Override
    public Optional<AgentQualityRule> findRuleByKey(String key) {
        return selectOne(RULES, AgentQualityRule.class, "data ->> ? = ?", "ruleKey", key);
    }

    @Override
    public List<AgentQualityRule> listRules() {
        return select(RULES, AgentQualityRule.class, " ORDER BY data ->> ? ASC", "ruleKey");
    }

    @Override
    public void insertRule(AgentQualityRule rule) {
        rules().insert(rule);
    }

    @Override
    public boolean insertRuleIfAbsent(AgentQualityRule rule) {
        return rules().insertIfAbsent(rule);
    }

    @Override
    public void replaceRule(AgentQualityRule rule) {
        rules().replace(rule);
    }

    @Override
    public Optional<AgentQualityRuleRevision> getRevision(String ruleId, int revision) {
        return selectOne(REVISIONS, AgentQualityRuleRevision.class,
            "data ->> ? = ? AND (data ->> ?)::int = ?", "ruleId", ruleId, "revision", revision);
    }

    @Override
    public List<AgentQualityRuleRevision> listRevisions(String ruleId) {
        return select(REVISIONS, AgentQualityRuleRevision.class,
            " WHERE data ->> ? = ? ORDER BY (data ->> ?)::int DESC", "ruleId", ruleId, "revision");
    }

    @Override
    public void insertRevision(AgentQualityRuleRevision revision) {
        revisions().insert(revision);
    }

    @Override
    public boolean insertRevisionIfAbsent(AgentQualityRuleRevision revision) {
        return revisions().insertIfAbsent(revision);
    }

    @Override
    public void replaceRevision(AgentQualityRuleRevision revision) {
        revisions().replace(revision);
    }

    @Override
    public Optional<AgentQualityPolicyRevision> getPolicy(int revision) {
        return selectOne(POLICIES, AgentQualityPolicyRevision.class,
            "(data ->> ?)::int = ?", "revision", revision);
    }

    @Override
    public List<AgentQualityPolicyRevision> listPolicies() {
        return select(POLICIES, AgentQualityPolicyRevision.class,
            " ORDER BY (data ->> ?)::int DESC", "revision");
    }

    @Override
    public void insertPolicy(AgentQualityPolicyRevision policy) {
        policies().insert(policy);
    }

    @Override
    public boolean insertPolicyIfAbsent(AgentQualityPolicyRevision policy) {
        return policies().insertIfAbsent(policy);
    }

    @Override
    public Optional<AgentQualityPolicyHead> getHead(String scope) {
        return selectOne(HEADS, AgentQualityPolicyHead.class, "data ->> ? = ?", "scope", scope);
    }

    @Override
    public boolean insertHeadIfAbsent(AgentQualityPolicyHead head) {
        return heads().insertIfAbsent(head);
    }

    @Override
    public boolean replaceHead(AgentQualityPolicyHead head, int expectedGeneration) {
        return database.execute("UPDATE " + HEADS + " SET data = ?::jsonb, updated_at = now() "
                + "WHERE id = ? AND (data ->> ?)::int = ?",
            JsonUtil.toJson(head), head.id, "generation", expectedGeneration) > 0;
    }

    @Override
    public void insertRun(AgentQualityRun run) {
        runs().insert(run);
    }

    @Override
    public Optional<AgentQualityRun> getRun(String runId) {
        return runs().get(runId);
    }

    @Override
    public Optional<AgentQualityRun> findRunByOperationKey(String operationKeyHash) {
        return selectOne(RUNS, AgentQualityRun.class, "data ->> ? = ?", "operationKeyHash", operationKeyHash);
    }

    @Override
    public Optional<AgentQualityRun> findLatestRun(String agentId) {
        return selectOne(RUNS, AgentQualityRun.class, "data ->> ? = ? ORDER BY data ->> ? DESC LIMIT 1",
            "agentId", agentId, "createdAt");
    }

    @Override
    public List<AgentQualityRun> listRuns(String agentId, int limit) {
        return select(RUNS, AgentQualityRun.class,
            " WHERE data ->> ? = ? ORDER BY data ->> ? DESC LIMIT ?", "agentId", agentId, "createdAt", limit);
    }

    @Override
    @SuppressWarnings({"PMD.ConsecutiveLiteralAppends", "PMD.AppendCharacterWithChar",
        "PMD.InsufficientStringBufferDeclaration"})
    public Map<String, AgentQualityRun> findLatestCompletedRuns(Collection<String> agentIds,
                                                                Set<QualityGateMode> modes) {
        if (modes == null || modes.isEmpty()) return Map.of();
        if (agentIds == null || agentIds.isEmpty()) return Map.of();
        var ids = new ArrayList<>(agentIds);
        var modeNames = modes.stream().map(Enum::name).toList();
        var sql = new StringBuilder("SELECT data::text AS data FROM (SELECT data, row_number() OVER (")
            .append("PARTITION BY data ->> ? ORDER BY data ->> ? DESC, id DESC) AS rn FROM ").append(RUNS)
            .append(" WHERE data ->> ? IN (").append(placeholders(ids.size())).append(")")
            .append(" AND data ->> ? = ?")
            .append(" AND data ->> ? IN (").append(placeholders(modeNames.size())).append(")")
            .append(") latest WHERE rn = 1");
        var params = new ArrayList<Object>();
        params.add("agentId");
        params.add("finishedAt");
        params.add("agentId");
        params.addAll(ids);
        params.add("runStatus");
        params.add(QualityRunStatus.COMPLETED.name());
        params.add("mode");
        params.addAll(modeNames);
        var result = new LinkedHashMap<String, AgentQualityRun>();
        for (var row : database.select(sql.toString(), PgDocumentRow.class, params.toArray())) {
            var run = JsonUtil.fromJson(AgentQualityRun.class, row.data);
            if (run.agentId != null) result.put(run.agentId, run);
        }
        return result;
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    @Override
    public boolean updateRun(AgentQualityRun run, QualityRunStatus expectedStatus) {
        return database.execute("UPDATE " + RUNS + " SET data = ?::jsonb, updated_at = now() "
                + "WHERE id = ? AND data ->> ? = ?",
            JsonUtil.toJson(run), run.id, "runStatus", expectedStatus.name()) > 0;
    }

    @Override
    public void insertPublicationRecord(AgentPublicationRecord record) {
        publications().insert(record);
    }

    @Override
    public Optional<AgentPublicationRecord> findPublicationByOperationKey(String operationKeyHash) {
        return selectOne(PUBLICATIONS, AgentPublicationRecord.class, "data ->> ? = ?",
            "operationKeyHash", operationKeyHash);
    }

    @Override
    public List<AgentPublicationRecord> listPublicationRecords(String agentId, int limit) {
        return select(PUBLICATIONS, AgentPublicationRecord.class,
            " WHERE data ->> ? = ? ORDER BY data ->> ? DESC LIMIT ?", "agentId", agentId, "publishedAt", limit);
    }

    private <T> Optional<T> selectOne(String table, Class<T> type, String condition, Object... params) {
        var sql = "SELECT data::text AS data FROM " + table + " WHERE " + condition;
        return database.selectOne(sql, PgDocumentRow.class, params)
            .map(row -> JsonUtil.fromJson(type, row.data));
    }

    private <T> List<T> select(String table, Class<T> type, String suffix, Object... params) {
        var sql = new StringBuilder("SELECT data::text AS data FROM ").append(table);
        if (suffix.startsWith(" ")) {
            sql.append(suffix);
        } else {
            sql.append(" WHERE ").append(suffix);
        }
        var result = new ArrayList<T>();
        for (var row : database.select(sql.toString(), PgDocumentRow.class, params)) {
            result.add(JsonUtil.fromJson(type, row.data));
        }
        return result;
    }
}
