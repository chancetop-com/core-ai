package ai.core.server.pg;

import ai.core.server.domain.MarketplaceRepo;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.skill.PgMarketplaceRepoStore;
import ai.core.server.skill.PgSkillCountRow;
import ai.core.server.skill.PgSkillStore;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgSkillStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("skill-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDocumentRow.class);
        database.view(PgSkillCountRow.class);
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
            for (var table : new String[]{"skills", "marketplace_repos"}) {
                statement.execute("CREATE TABLE IF NOT EXISTS app." + table
                    + " (id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize skill store test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private static <T> T withDatabase(T store) {
        try {
            var field = store.getClass().getDeclaredField("database");
            field.setAccessible(true);
            field.set(store, DATABASE);
            return store;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void skillStoreQueriesAndStats() {
        var store = withDatabase(new PgSkillStore());
        var namespace = "test-" + UUID.randomUUID();
        var skill = skill(namespace, "code-review", SkillSourceType.UPLOAD);
        skill.content = "full body";
        var resource = new SkillResource();
        resource.path = "scripts/check.sh";
        resource.content = "#!/bin/sh";
        skill.resources = List.of(resource);
        try {
            store.insert(skill);

            assertTrue(store.get(skill.id).isPresent());
            assertEquals(skill.id, store.findByQualifiedName(namespace + "/code-review").orElseThrow().id);
            assertEquals(1, store.findByName("code-review").size());
            assertEquals(1, store.listByIds(List.of(skill.id)).size());
            assertEquals(1, store.list(namespace, SkillSourceType.UPLOAD, 0, 10).size());
            assertEquals(1, store.count(namespace, SkillSourceType.UPLOAD));
            assertTrue(store.listBySourceType(SkillSourceType.UPLOAD).stream().anyMatch(item -> item.id.equals(skill.id)));
            assertEquals(1, store.countByNamespace(namespace));

            var catalog = store.listCatalog().stream().filter(item -> item.id.equals(skill.id)).findFirst().orElseThrow();
            assertNull(catalog.content, "catalog projection must drop skill content");
            assertNull(catalog.resources.getFirst().content, "catalog projection must drop resource content");
            assertEquals("scripts/check.sh", catalog.resources.getFirst().path);

            store.recordShow(namespace + "/code-review");
            store.recordPull(skill.id);
            var updated = store.get(skill.id).orElseThrow();
            assertEquals(1, updated.stats.showCount);
            assertEquals(1, updated.stats.pullCount);
            assertNotNull(updated.stats.lastPulledAt);
        } finally {
            store.delete(skill.id);
        }
        assertFalse(store.get(skill.id).isPresent());
    }

    @Test
    void marketplaceRepoStoreRoundTrip() {
        var store = withDatabase(new PgMarketplaceRepoStore());
        var repo = new MarketplaceRepo();
        repo.id = UUID.randomUUID().toString();
        repo.name = "owner/repo-" + repo.id;
        repo.repoUrl = "https://github.com/owner/repo";
        repo.branch = "main";
        repo.skillPath = "skills";
        repo.createdAt = ZonedDateTime.now();
        repo.updatedAt = ZonedDateTime.now();
        try {
            store.insert(repo);
            assertEquals(repo.id, store.get(repo.id).orElseThrow().id);
            assertTrue(store.listAll().stream().anyMatch(item -> item.id.equals(repo.id)));
        } finally {
            store.delete(repo.id);
        }
    }

    private SkillDefinition skill(String namespace, String name, SkillSourceType sourceType) {
        var skill = new SkillDefinition();
        skill.id = UUID.randomUUID().toString();
        skill.namespace = namespace;
        skill.name = name;
        skill.qualifiedName = namespace + "/" + name;
        skill.description = "description";
        skill.sourceType = sourceType;
        skill.userId = "user-1";
        skill.metadata = Map.of("category", "review");
        skill.createdAt = ZonedDateTime.now();
        skill.updatedAt = ZonedDateTime.now();
        return skill;
    }
}
