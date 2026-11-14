package ai.core.server.migration;

import ai.core.server.apimcp.serviceapi.domain.ServiceApi;
import ai.core.server.channel.ChannelConfigView;
import ai.core.server.domain.ApiKey;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.MarketplaceRepo;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectReportDraft;
import ai.core.server.domain.ProjectStatsEntity;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.server.domain.ProjectTargetScan;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SystemPrompt;
import ai.core.server.domain.SystemSettings;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.User;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.project.PgProjectCountRow;
import ai.core.server.project.PgProjectRow;
import ai.core.server.project.PgProjectStore;
import ai.core.server.trace.domain.PromptTemplate;
import ai.core.server.trigger.domain.Trigger;
import ai.core.server.user.PgUserRow;
import ai.core.server.user.PgUserIdRow;
import ai.core.server.user.PgUserStore;
import ai.core.utils.JsonUtil;
import com.mongodb.ConnectionString;
import core.framework.db.Database;
import core.framework.internal.db.DatabaseImpl;
import core.framework.mongo.impl.MongoImpl;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * One-off G1 Mongo -> PostgreSQL copy (identity, config and project collections have no startup
 * migration service; this is the tool used to seed them). Run with G1_COPY=1 after port-forwarding
 * the target Mongo and PostgreSQL. The copy is idempotent: every table upserts by its original id.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "G1_COPY", matches = "1")
class G1CopyTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(G1CopyTest.class);
    private static final String MONGO_URI = System.getenv().getOrDefault("G1_MONGO_URI",
        "mongodb://localhost:27018/core-ai?directConnection=true");
    private static final String PG_URL = System.getenv().getOrDefault("G1_PG_URL",
        "jdbc:postgresql://localhost:15434/core_ai");
    private final Map<Class<?>, MongoCollection<?>> collections = new java.util.HashMap<>();

    private MongoImpl mongo() {
        var mongo = new MongoImpl();
        mongo.uri = new ConnectionString(MONGO_URI);
        for (var type : List.of(User.class, ApiKey.class, Project.class, ProjectSubject.class,
            ProjectSubjectEvent.class, ProjectSubjectAttribution.class, ProjectTargetScan.class,
            ProjectReportDraft.class, ProjectStatsEntity.class, SystemSettings.class, SystemPrompt.class,
            PromptTemplate.class, Trigger.class, ServiceApi.class, GatewayProviderConfig.class,
            GatewayModelConfig.class, ToolRegistryEntry.class, SkillDefinition.class, MarketplaceRepo.class,
            ChannelConfigView.class, ai.core.server.channel.openclaw.OcgConfigView.class)) {
            register(mongo, type);
        }
        mongo.initialize();
        return mongo;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(MongoImpl mongo, Class<?> type) {
        collections.put(type, mongo.collection((Class) type));
    }

    private DatabaseImpl database() {
        var database = new DatabaseImpl("g1-dev-copy");
        database.url(PG_URL);
        database.user = System.getenv().getOrDefault("G1_PG_USER", "core_ai");
        database.password = System.getenv("G1_PG_PASSWORD");
        database.view(PgDocumentRow.class);
        database.view(PgProjectCountRow.class);
        database.view(PgUserRow.class);
        database.view(PgUserIdRow.class);
        database.view(PgProjectRow.class);
        return database;
    }

    @Test
    void copiesG1Idempotently() {
        var mongo = mongo();
        var database = database();
        copy(mongo, database);
        var first = counts(database);
        copy(mongo, database);
        var second = counts(database);
        assertEquals(first, second, "second copy must not change row counts");
        verifySamples(database);
        LOGGER.info("G1 copy counts={}", second);
    }

    private void copy(MongoImpl mongo, DatabaseImpl database) {
        copyUsers(mongo, database);
        copyApiKeys(database);
        copyProjects(mongo, database);
        copyJson(database, ProjectSubject.class, "app.project_subjects", s -> s.id);
        copyJson(database, ProjectSubjectEvent.class, "app.project_subject_events", e -> e.id);
        copyJson(database, ProjectSubjectAttribution.class, "app.project_subject_attributions", a -> a.id);
        copyJson(database, ProjectTargetScan.class, "app.project_target_scans", s -> s.id);
        copyJson(database, ProjectReportDraft.class, "app.project_report_drafts", d -> d.id);
        copyJson(database, ProjectStatsEntity.class, "app.project_stats", s -> s.id);
        copyJson(database, SystemSettings.class, "app.system_settings", s -> s.id);
        copyJson(database, SystemPrompt.class, "app.system_prompts", s -> s.id);
        copyJson(database, PromptTemplate.class, "app.prompt_templates", p -> p.id);
        copyJson(database, Trigger.class, "app.triggers", t -> t.id);
        copyJson(database, ServiceApi.class, "app.service_api", s -> s.id);
        copyJson(database, GatewayProviderConfig.class, "app.gateway_provider", p -> p.id);
        copyJson(database, GatewayModelConfig.class, "app.gateway_model", m -> m.id);
        copyJson(database, ToolRegistryEntry.class, "app.tool_registry", t -> t.id);
        copyJson(database, SkillDefinition.class, "app.skills", s -> s.id);
        copyJson(database, MarketplaceRepo.class, "app.marketplace_repos", r -> r.id);
        copyJson(database, ChannelConfigView.class, "app.channels", c -> c.channelId);
        copyJson(database, ai.core.server.channel.openclaw.OcgConfigView.class, "app.ocg_configs", c -> c.id);
    }

    private void copyUsers(MongoImpl mongo, DatabaseImpl database) {
        var store = new PgUserStore();
        inject(store, database);
        for (var user : all(User.class)) {
            if (store.get(user.id).isPresent()) {
                store.replace(user);
            } else {
                store.insert(user);
            }
        }
    }

    private void copyApiKeys(DatabaseImpl database) {
        var keys = all(ApiKey.class);
        for (var batch = 0; batch < keys.size(); batch += 50) {
            var rows = keys.subList(batch, Math.min(batch + 50, keys.size()));
            var values = String.join(", ", Collections.nCopies(rows.size(), "(?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)"));
            var params = new ArrayList<Object>();
            for (var key : rows) {
                params.add(key.id);
                params.add(key.keyHash);
                params.add(key.keyPrefix);
                params.add(key.userId);
                params.add(key.scope);
                params.add(key.metadata != null ? JsonUtil.toJson(key.metadata) : null);
                params.add(key.status);
                params.add(key.expiresAt);
                params.add(key.createdAt);
                params.add(key.lastUsedAt);
                params.add(key.revokedAt);
            }
            database.execute("INSERT INTO app.api_keys (id, key_hash, key_prefix, user_id, scope, metadata, status, "
                    + "expires_at, created_at, last_used_at, revoked_at) VALUES " + values
                    + " ON CONFLICT (id) DO UPDATE SET key_hash = EXCLUDED.key_hash, key_prefix = EXCLUDED.key_prefix, "
                    + "user_id = EXCLUDED.user_id, scope = EXCLUDED.scope, metadata = EXCLUDED.metadata, "
                    + "status = EXCLUDED.status, expires_at = EXCLUDED.expires_at, created_at = EXCLUDED.created_at, "
                    + "last_used_at = EXCLUDED.last_used_at, revoked_at = EXCLUDED.revoked_at",
                params.toArray());
        }
    }

    private void copyProjects(MongoImpl mongo, DatabaseImpl database) {
        var store = new PgProjectStore();
        inject(store, database);
        for (var project : all(Project.class)) {
            // legacy prod projects predate updated_at; the PG column is NOT NULL
            if (project.updatedAt == null) {
                project.updatedAt = project.createdAt != null ? project.createdAt : java.time.ZonedDateTime.now();
            }
            if (store.get(project.id).isPresent()) {
                store.update(project.id, target -> copyFields(project, target));
            } else {
                store.insert(project);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> all(Class<T> type) {
        return ((MongoCollection<T>) collections.get(type)).find(new Query());
    }

    private <T> void copyJson(DatabaseImpl database, Class<T> type, String table, Function<T, String> id) {
        var entities = all(type);
        int size = type == SkillDefinition.class ? 5 : 100;
        for (var batch = 0; batch < entities.size(); batch += size) {
            var rows = entities.subList(batch, Math.min(batch + size, entities.size()));
            var values = String.join(", ", Collections.nCopies(rows.size(), "(?, ?::jsonb, now())"));
            var params = new ArrayList<Object>();
            for (var entity : rows) {
                params.add(id.apply(entity));
                params.add(json(entity));
            }
            database.execute("INSERT INTO " + table + " (id, data, updated_at) VALUES " + values
                    + " ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()", params.toArray());
        }
    }

    private String json(Object entity) {
        // PostgreSQL jsonb cannot contain \u0000 and rejects legacy invalid escapes such as \-.
        return repairEscapes(JsonUtil.toJson(entity).replace("\\u0000", ""));
    }

    private String repairEscapes(String json) {
        var repaired = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c != '\\') {
                repaired.append(c);
                continue;
            }
            repaired.append('\\');
            if (i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"' || next == '\\' || next == '/' || next == 'b' || next == 'f'
                    || next == 'n' || next == 'r' || next == 't' || next == 'u') {
                    repaired.append(next);
                    i++;
                } else {
                    repaired.append('\\');
                }
            }
        }
        return repaired.toString();
    }

    private Map<String, Long> counts(Database database) {
        var result = new java.util.LinkedHashMap<String, Long>();
        for (var table : List.of("users", "api_keys", "projects", "project_subjects", "project_subject_events",
            "project_subject_attributions", "project_target_scans", "project_report_drafts", "project_stats",
            "system_prompts", "prompt_templates", "triggers", "service_api", "gateway_provider", "gateway_model",
            "tool_registry", "skills", "marketplace_repos", "channels", "ocg_configs", "system_settings")) {
            result.put(table, database.selectOne("SELECT count(*) AS total FROM app." + table, PgProjectCountRow.class)
                .map(row -> row.total).orElse(0L));
        }
        return result;
    }

    private void verifySamples(DatabaseImpl database) {
        // prod does not have every collection; only verify that a copied row reads back with its fields
        var users = all(User.class);
        if (!users.isEmpty()) {
            var user = users.getFirst();
            assertEquals(user.name, storedJson(database, "app.users", user.id).get("name"));
        }
        var projects = all(Project.class);
        if (!projects.isEmpty()) {
            var project = projects.getFirst();
            assertEquals(project.name, storedJson(database, "app.projects", project.id).get("name"));
        }
        var skills = all(SkillDefinition.class);
        if (!skills.isEmpty()) {
            var skill = skills.getFirst();
            assertEquals(skill.qualifiedName, storedJson(database, "app.skills", skill.id).get("qualifiedName"));
        }
        var channels = all(ChannelConfigView.class);
        if (!channels.isEmpty()) {
            var channel = channels.getFirst();
            assertEquals(channel.channelType, storedJson(database, "app.channels", channel.channelId).get("channelType"));
        }
        var providers = all(GatewayProviderConfig.class);
        if (!providers.isEmpty()) {
            var provider = providers.getFirst();
            assertEquals(provider.name, storedJson(database, "app.gateway_provider", provider.id).get("name"));
        }
        var tools = all(ToolRegistryEntry.class);
        if (!tools.isEmpty()) {
            var tool = tools.getFirst();
            assertEquals(tool.name, storedJson(database, "app.tool_registry", tool.id).get("name"));
        }
        LOGGER.info("G1 samples verified");
    }

    private Map<String, Object> storedJson(Database database, String table, String id) {
        var row = database.selectOne("SELECT data::text AS data FROM " + table + " WHERE id = ?", PgDocumentRow.class, id)
            .orElseThrow();
        var map = JsonUtil.toMap(row.data);
        assertNotNull(map);
        return map;
    }

    private void inject(Object store, Database database) {
        try {
            Field field = store.getClass().getDeclaredField("database");
            field.setAccessible(true);
            field.set(store, database);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void copyFields(Object source, Object target) {
        for (var field : source.getClass().getFields()) {
            try {
                field.set(target, field.get(source));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

