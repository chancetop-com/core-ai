package ai.core.server.skill.quality;

import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.server.skill.quality.domain.SkillQualityPolicyHead;
import ai.core.server.skill.quality.domain.SkillQualityPolicyRevision;
import ai.core.server.skill.quality.domain.SkillQualityRule;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * JSONB-document implementation. Filtering only touches fields the gate queries; the row payload stays
 * serialized as one document.
 *
 * @author core-ai
 */
public class PgSkillQualityStore implements SkillQualityStore {
    private static final String RULES = "app.skill_quality_rules";
    private static final String REVISIONS = "app.skill_quality_rule_revisions";
    private static final String POLICIES = "app.skill_quality_policy_revisions";
    private static final String HEADS = "app.skill_quality_policy_heads";

    @Inject
    public Database database;

    private <T> PgDocumentStore<T> store(String table, Class<T> type, Function<T, String> id) {
        return new PgDocumentStore<>(database, table, type, id);
    }

    private PgDocumentStore<SkillQualityRule> rules() {
        return store(RULES, SkillQualityRule.class, rule -> rule.id);
    }

    private PgDocumentStore<SkillQualityRuleRevision> revisions() {
        return store(REVISIONS, SkillQualityRuleRevision.class, revision -> revision.id);
    }

    private PgDocumentStore<SkillQualityPolicyRevision> policies() {
        return store(POLICIES, SkillQualityPolicyRevision.class, policy -> policy.id);
    }

    private PgDocumentStore<SkillQualityPolicyHead> heads() {
        return store(HEADS, SkillQualityPolicyHead.class, head -> head.id);
    }

    @Override
    public Optional<SkillQualityRule> getRule(String id) {
        return rules().get(id);
    }

    @Override
    public Optional<SkillQualityRule> findRuleByKey(String key) {
        return selectOne(RULES, SkillQualityRule.class, "data ->> ? = ?", "ruleKey", key);
    }

    @Override
    public List<SkillQualityRule> listRules() {
        return select(RULES, SkillQualityRule.class, " ORDER BY data ->> ? ASC", "ruleKey");
    }

    @Override
    public void insertRule(SkillQualityRule rule) {
        rules().insert(rule);
    }

    @Override
    public boolean insertRuleIfAbsent(SkillQualityRule rule) {
        return rules().insertIfAbsent(rule);
    }

    @Override
    public void replaceRule(SkillQualityRule rule) {
        rules().replace(rule);
    }

    @Override
    public Optional<SkillQualityRuleRevision> getRevision(String ruleId, int revision) {
        return selectOne(REVISIONS, SkillQualityRuleRevision.class,
            "data ->> ? = ? AND (data ->> ?)::int = ?", "ruleId", ruleId, "revision", revision);
    }

    @Override
    public List<SkillQualityRuleRevision> listRevisions(String ruleId) {
        return select(REVISIONS, SkillQualityRuleRevision.class,
            " WHERE data ->> ? = ? ORDER BY (data ->> ?)::int DESC", "ruleId", ruleId, "revision");
    }

    @Override
    public void insertRevision(SkillQualityRuleRevision revision) {
        revisions().insert(revision);
    }

    @Override
    public boolean insertRevisionIfAbsent(SkillQualityRuleRevision revision) {
        return revisions().insertIfAbsent(revision);
    }

    @Override
    public void replaceRevision(SkillQualityRuleRevision revision) {
        revisions().replace(revision);
    }

    @Override
    public Optional<SkillQualityPolicyRevision> getPolicy(int revision) {
        return selectOne(POLICIES, SkillQualityPolicyRevision.class, "(data ->> ?)::int = ?", "revision", revision);
    }

    @Override
    public List<SkillQualityPolicyRevision> listPolicies() {
        return select(POLICIES, SkillQualityPolicyRevision.class, " ORDER BY (data ->> ?)::int DESC", "revision");
    }

    @Override
    public void insertPolicy(SkillQualityPolicyRevision policy) {
        policies().insert(policy);
    }

    @Override
    public boolean insertPolicyIfAbsent(SkillQualityPolicyRevision policy) {
        return policies().insertIfAbsent(policy);
    }

    @Override
    public Optional<SkillQualityPolicyHead> getHead(String scope) {
        return selectOne(HEADS, SkillQualityPolicyHead.class, "data ->> ? = ?", "scope", scope);
    }

    @Override
    public boolean insertHeadIfAbsent(SkillQualityPolicyHead head) {
        return heads().insertIfAbsent(head);
    }

    @Override
    public boolean replaceHead(SkillQualityPolicyHead head, int expectedGeneration) {
        return database.execute("UPDATE " + HEADS + " SET data = ?::jsonb, updated_at = now() "
                + "WHERE id = ? AND (data ->> ?)::int = ?",
            JsonUtil.toJson(head), head.id, "generation", expectedGeneration) > 0;
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
