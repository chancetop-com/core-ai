package ai.core.server;

import ai.core.api.server.DatasetWebService;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.migration.DatasetMigrationService;
import ai.core.server.dataset.store.DatasetMetaStore;
import ai.core.server.dataset.store.DatasetRecordStore;
import ai.core.server.dataset.store.DatasetSqlExecutor;
import ai.core.server.dataset.store.DatasetViewManager;
import ai.core.server.dataset.store.DatasetViewReconciler;
import ai.core.server.dataset.store.MongoDatasetMetaStore;
import ai.core.server.dataset.store.MongoDatasetRecordStore;
import ai.core.server.dataset.store.MongoNativeDatasetRecordStore;
import ai.core.server.dataset.store.PgBoolRow;
import ai.core.server.dataset.store.PgCountRow;
import ai.core.server.dataset.store.PgDatasetMetaStore;
import ai.core.server.dataset.store.PgDatasetRecordRow;
import ai.core.server.dataset.store.PgDatasetRow;
import ai.core.server.dataset.store.PgDatasetViewRow;
import ai.core.server.dataset.store.PgDdl;
import ai.core.server.dataset.store.PgIdRow;
import ai.core.server.dataset.store.PgJsonRow;
import ai.core.server.dataset.store.PgSizeRow;
import ai.core.server.dataset.store.PostgresDatasetRecordStore;
import ai.core.server.web.DatasetWebServiceImpl;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClients;
import core.framework.module.Module;

import java.time.Duration;

/**
 * Picks the dataset store with {@code sys.dataset.store} (mongo | mongo-native | postgresql). Metadata and
 * records switch together: the PostgreSQL branch moves both to the same database/transaction, so a schema
 * change and its view rebuild cannot diverge.
 *
 * <p>All store properties are read up front because core-ng rejects properties that are never read; the
 * unused branch must still mark its keys as used.
 *
 * @author stephen
 */
public class DatasetModule extends Module {
    @Override
    protected void initialize() {
        var store = property("sys.dataset.store").orElse("mongo");
        var dbUrl = property("sys.db.url").orElse(null);
        var dbUser = property("sys.db.user").orElse(null);
        var dbPassword = property("sys.db.password").orElse(null);
        var migrationEnabled = Boolean.parseBoolean(property("sys.dataset.migration.enabled").orElse("false"));
        var sqlExecutorEnabled = Boolean.parseBoolean(property("sys.dataset.sqlExecutor.enabled").orElse("false"));
        var sqlExecutorUser = property("sys.dataset.sqlExecutor.user").orElse(null);
        var sqlExecutorPassword = property("sys.dataset.sqlExecutor.password").orElse(null);
        var mongoNativeCollection = property("sys.dataset.mongoNativeCollection").orElse("dataset_records_v2");
        switch (store) {
            case "postgresql" -> bindPostgres(dbUrl, dbUser, dbPassword, sqlExecutorEnabled, sqlExecutorUser, sqlExecutorPassword);
            case "mongo-native" -> {
                bindMongoNative(mongoNativeCollection);
                if (migrationEnabled) bindMigration(dbUrl, dbUser, dbPassword);
            }
            default -> {
                bindMongo();
                if (migrationEnabled) bindMigration(dbUrl, dbUser, dbPassword);
            }
        }
        bind(DatasetService.class);
        bind(DatasetRecordService.class);
        api().service(DatasetWebService.class, bind(DatasetWebServiceImpl.class));
    }

    private void bindMongo() {
        bind(DatasetMetaStore.class, bind(MongoDatasetMetaStore.class));
        bind(DatasetRecordStore.class, bind(MongoDatasetRecordStore.class));
    }

    private void bindMongoNative(String collectionName) {
        bind(DatasetMetaStore.class, bind(MongoDatasetMetaStore.class));
        var uri = requiredProperty("sys.mongo.uri");
        var client = MongoClients.create(uri);
        onShutdown(client::close);
        var database = new ConnectionString(uri).getDatabase();
        if (database == null) throw new Error("mongo uri must include a database, uri=" + uri);
        var store = new MongoNativeDatasetRecordStore(client, database, collectionName);
        store.ensureIndexes();
        bind(DatasetRecordStore.class, bind(store));
    }

    private void bindPostgres(String url, String user, String password, boolean sqlExecutorEnabled,
                              String sqlExecutorUser, String sqlExecutorPassword) {
        configureDatasetViews();
        bind(PgDdl.class, new PgDdl(url, user, password));
        bind(DatasetViewManager.class);
        bind(DatasetMetaStore.class, bind(PgDatasetMetaStore.class));
        bind(DatasetRecordStore.class, bind(PostgresDatasetRecordStore.class));
        var reconciler = bind(DatasetViewReconciler.class);
        onStartup(reconciler::reconcile);
        schedule().fixedRate("dataset-view-reconcile", reconciler, Duration.ofHours(24));
        if (sqlExecutorEnabled) {
            if (sqlExecutorUser == null || sqlExecutorPassword == null) {
                throw new Error("sys.dataset.sqlExecutor.user/password are required when the sql executor is enabled");
            }
            var executor = new DatasetSqlExecutor(url, user, password, sqlExecutorUser, sqlExecutorPassword);
            bind(DatasetSqlExecutor.class, executor);
            onStartup(executor::initialize);
        }
    }

    // runs while the application still reads and writes Mongo; the copy is idempotent and can be re-run
    private void bindMigration(String url, String user, String password) {
        configureDatasetViews();
        bind(PgDdl.class, new PgDdl(url, user, password));
        bind(DatasetViewManager.class);
        bind(PgDatasetMetaStore.class);
        bind(PostgresDatasetRecordStore.class);
        var migration = bind(DatasetMigrationService.class);
        onStartup(migration::migrate);
    }

    private void configureDatasetViews() {
        db().view(PgDatasetRecordRow.class);
        db().view(PgIdRow.class);
        db().view(PgJsonRow.class);
        db().view(PgCountRow.class);
        db().view(PgDatasetViewRow.class);
        db().view(PgDatasetRow.class);
        db().view(PgSizeRow.class);
        db().view(PgBoolRow.class);
    }
}
