package ai.core.server;

import ai.core.api.server.DatasetWebService;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.store.DatasetPgSchemaInitializer;
import ai.core.server.dataset.store.DatasetRecordStore;
import ai.core.server.dataset.store.DatasetSqlExecutor;
import ai.core.server.dataset.store.DatasetViewManager;
import ai.core.server.dataset.store.MongoDatasetRecordStore;
import ai.core.server.dataset.store.MongoNativeDatasetRecordStore;
import ai.core.server.dataset.store.PgCountRow;
import ai.core.server.dataset.store.PgDatasetRecordRow;
import ai.core.server.dataset.store.PgDatasetViewRow;
import ai.core.server.dataset.store.PgDdl;
import ai.core.server.dataset.store.PgJsonRow;
import ai.core.server.dataset.store.PgSizeRow;
import ai.core.server.dataset.store.PostgresDatasetRecordStore;
import ai.core.server.web.DatasetWebServiceImpl;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClients;
import core.framework.module.Module;

/**
 * Picks the dataset store with {@code sys.dataset.store} (mongo | mongo-native | postgresql). Only the
 * postgresql branch touches {@code db()}, so the default configuration keeps working without a database.
 *
 * @author stephen
 */
public class DatasetModule extends Module {
    @Override
    protected void initialize() {
        var store = property("sys.dataset.store").orElse("mongo");
        switch (store) {
            case "postgresql" -> bindPostgres();
            case "mongo-native" -> bindMongoNative();
            default -> bind(DatasetRecordStore.class, bind(MongoDatasetRecordStore.class));
        }
        bind(DatasetService.class);
        bind(DatasetRecordService.class);
        api().service(DatasetWebService.class, bind(DatasetWebServiceImpl.class));
    }

    private void bindPostgres() {
        var url = requiredProperty("sys.db.url");
        var user = requiredProperty("sys.db.user");
        var password = requiredProperty("sys.db.password");
        db().url(url);
        db().user(user);
        db().password(password);
        db().view(PgDatasetRecordRow.class);
        db().view(PgJsonRow.class);
        db().view(PgCountRow.class);
        db().view(PgDatasetViewRow.class);
        db().view(PgSizeRow.class);
        var ddl = new PgDdl(url, user, password);
        bind(PgDdl.class, ddl);
        bind(DatasetViewManager.class);
        var store = bind(PostgresDatasetRecordStore.class);
        bind(DatasetRecordStore.class, store);
        var initializer = bind(DatasetPgSchemaInitializer.class);
        onStartup(initializer::initialize);
        if (Boolean.parseBoolean(property("sys.dataset.sqlExecutor.enabled").orElse("false"))) {
            var executor = new DatasetSqlExecutor(url, user, password,
                    requiredProperty("sys.dataset.sqlExecutor.user"),
                    requiredProperty("sys.dataset.sqlExecutor.password"));
            bind(DatasetSqlExecutor.class, executor);
            onStartup(executor::initialize);
        }
    }

    private void bindMongoNative() {
        var uri = requiredProperty("sys.mongo.uri");
        var client = MongoClients.create(uri);
        onShutdown(client::close);
        var database = new ConnectionString(uri).getDatabase();
        if (database == null) throw new Error("mongo uri must include a database, uri=" + uri);
        var collection = property("sys.dataset.mongoNativeCollection").orElse("dataset_records_v2");
        var store = new MongoNativeDatasetRecordStore(client, database, collection);
        store.ensureIndexes();
        bind(DatasetRecordStore.class, bind(store));
    }
}
