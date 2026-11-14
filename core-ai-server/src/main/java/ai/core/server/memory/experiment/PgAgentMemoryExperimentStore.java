package ai.core.server.memory.experiment;

import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class PgAgentMemoryExperimentStore implements AgentMemoryExperimentStore {
    private static final String CONFIG_TABLE = "app.agent_memory_experiment_configs";
    private static final String CONFIG_COLUMNS = "id, agent_id, enabled, injection_probability, "
        + "enabled_layers::text AS enabled_layers, top_k, ranking_strategy, injection_mode, created_at, updated_at";
    private static final String CONFIG_UPSERT = "INSERT INTO " + CONFIG_TABLE + " (id, agent_id, enabled, "
        + "injection_probability, enabled_layers, top_k, ranking_strategy, injection_mode, created_at, updated_at) "
        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
        + "agent_id = EXCLUDED.agent_id, enabled = EXCLUDED.enabled, "
        + "injection_probability = EXCLUDED.injection_probability, enabled_layers = EXCLUDED.enabled_layers, "
        + "top_k = EXCLUDED.top_k, ranking_strategy = EXCLUDED.ranking_strategy, "
        + "injection_mode = EXCLUDED.injection_mode, created_at = EXCLUDED.created_at, "
        + "updated_at = EXCLUDED.updated_at";

    private static final String RUN_TABLE = "app.agent_memory_experiment_runs";
    private static final String RUN_COLUMNS = "id, agent_id, session_id, run_id, enabled, "
        + "enabled_layers::text AS enabled_layers, ranking_strategy, top_k, injection_probability, injection_mode, "
        + "injection_decision, injected_memory_ids::text AS injected_memory_ids, injected_memory_count, "
        + "layer_breakdown::text AS layer_breakdown, prompt_tokens, attributions::text AS attributions, outcome, "
        + "user_rating, created_at, updated_at";
    private static final String RUN_UPSERT = "INSERT INTO " + RUN_TABLE + " (id, agent_id, session_id, run_id, "
        + "enabled, enabled_layers, ranking_strategy, top_k, injection_probability, injection_mode, injection_decision, "
        + "injected_memory_ids, injected_memory_count, layer_breakdown, prompt_tokens, attributions, outcome, "
        + "user_rating, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, "
        + "?::jsonb, ?, ?::jsonb, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET agent_id = EXCLUDED.agent_id, "
        + "session_id = EXCLUDED.session_id, run_id = EXCLUDED.run_id, enabled = EXCLUDED.enabled, "
        + "enabled_layers = EXCLUDED.enabled_layers, ranking_strategy = EXCLUDED.ranking_strategy, "
        + "top_k = EXCLUDED.top_k, injection_probability = EXCLUDED.injection_probability, "
        + "injection_mode = EXCLUDED.injection_mode, injection_decision = EXCLUDED.injection_decision, "
        + "injected_memory_ids = EXCLUDED.injected_memory_ids, "
        + "injected_memory_count = EXCLUDED.injected_memory_count, layer_breakdown = EXCLUDED.layer_breakdown, "
        + "prompt_tokens = EXCLUDED.prompt_tokens, attributions = EXCLUDED.attributions, outcome = EXCLUDED.outcome, "
        + "user_rating = EXCLUDED.user_rating, created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at";

    private static String json(Object value) {
        return value == null ? null : JsonSanitizer.json(JsonUtil.toJson(value));
    }

    private static <T> T value(String json, TypeReference<T> type) {
        return json == null ? null : JsonUtil.fromJson(type, json);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Object[] configParams(AgentMemoryExperimentConfig config) {
        return new Object[]{
            config.id, JsonSanitizer.text(config.agentId), config.enabled, config.injectionProbability,
            json(config.enabledLayers), config.topK, name(config.rankingStrategy), name(config.injectionMode),
            config.createdAt, config.updatedAt
        };
    }

    private static AgentMemoryExperimentConfig toConfig(PgMemoryExperimentConfigRow row) {
        var config = new AgentMemoryExperimentConfig();
        config.id = row.id;
        config.agentId = row.agentId;
        config.enabled = row.enabled;
        config.injectionProbability = row.injectionProbability;
        config.enabledLayers = value(row.enabledLayers, LAYERS_TYPE);
        config.topK = row.topK;
        config.rankingStrategy = parse(RankingStrategy.class, row.rankingStrategy);
        config.injectionMode = parse(InjectionMode.class, row.injectionMode);
        config.createdAt = row.createdAt;
        config.updatedAt = row.updatedAt;
        return config;
    }

    private static Object[] runParams(AgentMemoryExperimentRun run) {
        return new Object[]{
            run.id, JsonSanitizer.text(run.agentId), JsonSanitizer.text(run.sessionId), JsonSanitizer.text(run.runId),
            run.enabled, json(run.enabledLayers), name(run.rankingStrategy), run.topK, run.injectionProbability,
            name(run.injectionMode), run.injectionDecision, json(run.injectedMemoryIds), run.injectedMemoryCount,
            json(run.layerBreakdown), run.promptTokens, json(run.attributions), JsonSanitizer.text(run.outcome),
            run.userRating, run.createdAt, run.updatedAt
        };
    }

    private static AgentMemoryExperimentRun toRun(PgMemoryExperimentRunRow row) {
        var run = new AgentMemoryExperimentRun();
        run.id = row.id;
        run.agentId = row.agentId;
        run.sessionId = row.sessionId;
        run.runId = row.runId;
        run.enabled = row.enabled;
        run.enabledLayers = value(row.enabledLayers, LAYERS_TYPE);
        run.rankingStrategy = parse(RankingStrategy.class, row.rankingStrategy);
        run.topK = row.topK;
        run.injectionProbability = row.injectionProbability;
        run.injectionMode = parse(InjectionMode.class, row.injectionMode);
        run.injectionDecision = row.injectionDecision;
        run.injectedMemoryIds = value(row.injectedMemoryIds, STRING_LIST_TYPE);
        run.injectedMemoryCount = row.injectedMemoryCount;
        run.layerBreakdown = value(row.layerBreakdown, LAYER_COUNT_TYPE);
        run.promptTokens = row.promptTokens;
        run.attributions = value(row.attributions, ATTRIBUTION_LIST_TYPE);
        run.outcome = row.outcome;
        run.userRating = row.userRating;
        run.createdAt = row.createdAt;
        run.updatedAt = row.updatedAt;
        return run;
    }

    private static final TypeReference<List<MemoryLayer>> LAYERS_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, Integer>> LAYER_COUNT_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<MemoryAttribution>> ATTRIBUTION_LIST_TYPE = new TypeReference<>() { };

    @Inject
    Database database;

    @Override
    public AgentMemoryExperimentConfig getConfig(String agentId) {
        return database.selectOne("SELECT " + CONFIG_COLUMNS + " FROM " + CONFIG_TABLE + " WHERE agent_id = ? LIMIT 1",
                PgMemoryExperimentConfigRow.class, agentId)
            .map(PgAgentMemoryExperimentStore::toConfig).orElse(null);
    }

    @Override
    public void saveConfig(AgentMemoryExperimentConfig config) {
        database.execute(CONFIG_UPSERT, configParams(config));
    }

    @Override
    public List<AgentMemoryExperimentConfig> findConfigs(int skip, int limit) {
        return database.select("SELECT " + CONFIG_COLUMNS + " FROM " + CONFIG_TABLE
                + " ORDER BY agent_id OFFSET ? LIMIT ?", PgMemoryExperimentConfigRow.class, skip, limit)
            .stream().map(PgAgentMemoryExperimentStore::toConfig).toList();
    }

    @Override
    public long countConfigs() {
        var row = database.selectOne("SELECT count(*) AS count FROM " + CONFIG_TABLE, PgMemoryExperimentCountRow.class)
            .orElse(null);
        return row == null || row.count == null ? 0 : row.count;
    }

    @Override
    public void deleteConfig(String agentId) {
        database.execute("DELETE FROM " + CONFIG_TABLE + " WHERE agent_id = ?", agentId);
    }

    @Override
    public void insertRun(AgentMemoryExperimentRun run) {
        database.execute(RUN_UPSERT, runParams(run));
    }

    @Override
    public void updateRun(AgentMemoryExperimentRun run) {
        database.execute(RUN_UPSERT, runParams(run));
    }

    @Override
    public AgentMemoryExperimentRun latestRunBySession(String sessionId) {
        return database.selectOne("SELECT " + RUN_COLUMNS + " FROM " + RUN_TABLE
                + " WHERE session_id = ? ORDER BY created_at DESC LIMIT 1", PgMemoryExperimentRunRow.class, sessionId)
            .map(PgAgentMemoryExperimentStore::toRun).orElse(null);
    }

    @Override
    public AgentMemoryExperimentRun runByRunId(String runId) {
        return database.selectOne("SELECT " + RUN_COLUMNS + " FROM " + RUN_TABLE + " WHERE run_id = ? LIMIT 1",
                PgMemoryExperimentRunRow.class, runId)
            .map(PgAgentMemoryExperimentStore::toRun).orElse(null);
    }

    @Override
    public List<AgentMemoryExperimentRun> runsByAgent(String agentId, int limit) {
        return database.select("SELECT " + RUN_COLUMNS + " FROM " + RUN_TABLE
                + " WHERE agent_id = ? ORDER BY created_at DESC LIMIT ?", PgMemoryExperimentRunRow.class, agentId, limit)
            .stream().map(PgAgentMemoryExperimentStore::toRun).toList();
    }

    @Override
    public List<AgentMemoryExperimentRun> listRuns(String agentId, int skip, int limit) {
        var params = new ArrayList<Object>();
        var scoped = agentId != null && !agentId.isBlank();
        var sql = "SELECT " + RUN_COLUMNS + " FROM " + RUN_TABLE
            + (scoped ? " WHERE agent_id = ?" : "")
            + " ORDER BY created_at DESC OFFSET ? LIMIT ?";
        if (scoped) params.add(agentId);
        params.add(skip);
        params.add(limit);
        return database.select(sql, PgMemoryExperimentRunRow.class, params.toArray()).stream()
            .map(PgAgentMemoryExperimentStore::toRun).toList();
    }

    @Override
    public long countRuns(String agentId) {
        var sql = "SELECT count(*) AS count FROM " + RUN_TABLE;
        var row = agentId != null && !agentId.isBlank()
            ? database.selectOne(sql + " WHERE agent_id = ?", PgMemoryExperimentCountRow.class, agentId).orElse(null)
            : database.selectOne(sql, PgMemoryExperimentCountRow.class).orElse(null);
        return row == null || row.count == null ? 0 : row.count;
    }

    @Override
    public AgentMemoryExperimentRun getRun(String id) {
        return database.selectOne("SELECT " + RUN_COLUMNS + " FROM " + RUN_TABLE + " WHERE id = ? LIMIT 1",
                PgMemoryExperimentRunRow.class, id)
            .map(PgAgentMemoryExperimentStore::toRun).orElse(null);
    }
}
