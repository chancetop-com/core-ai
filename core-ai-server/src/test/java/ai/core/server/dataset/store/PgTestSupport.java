package ai.core.server.dataset.store;

import core.framework.db.Database;
import core.framework.internal.db.DatabaseImpl;

/**
 * Shared PostgreSQL setup for the PoC tests; every class gets its own pool so closing one does not break
 * another.
 *
 * @author stephen
 */
final class PgTestSupport {
    static String url() {
        return System.getenv("CORE_AI_PG_TEST_URL");
    }

    static String user() {
        return System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
    }

    static String password() {
        return System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
    }

    static PgDdl ddl() {
        return new PgDdl(url(), user(), password());
    }

    static DatasetViewManager viewManager(Database database) {
        var manager = new DatasetViewManager();
        manager.database = database;
        manager.ddl = ddl();
        return manager;
    }

    static DatabaseImpl database(String name) {
        var database = new DatabaseImpl(name);
        database.url(url());
        database.user = user();
        database.password = password();
        database.view(PgDatasetRecordRow.class);
        database.view(PgJsonRow.class);
        database.view(PgCountRow.class);
        database.view(PgDatasetViewRow.class);
        database.view(PgSizeRow.class);
        database.view(PgDatasetRow.class);
        database.view(DatasetViewManagerTest.ValueRow.class);
        database.view(DatasetViewManagerTest.RegionRow.class);
        schema(database);
        return database;
    }

    static void schema(Database database) {
        var initializer = new DatasetPgSchemaInitializer();
        initializer.ddl = ddl();
        initializer.initialize();
    }

    private PgTestSupport() {
    }
}
