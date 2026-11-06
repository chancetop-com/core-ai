package ai.core.server.dataset.store;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Rebuilds missing or stale dataset views after a crash or a partial deploy. The session advisory lock keeps
 * multiple pods from rebuilding the same views at once; a failed rebuild rolls back with the surrounding
 * transaction and is retried on the next run.
 *
 * @author stephen
 */
public class DatasetViewReconciler implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetViewReconciler.class);
    private static final String LOCK_KEY = "dataset_view_reconcile";

    @Inject
    PgDdl ddl;

    @Inject
    DatasetViewManager viewManager;

    @Inject
    PgDatasetMetaStore metaStore;

    @Override
    public void execute(JobContext context) {
        reconcile();
    }

    public void reconcile() {
        ddl.inTransaction(connection -> {
            if (!tryLock(connection)) {
                LOGGER.info("dataset view reconcile skipped, another instance holds the lock");
                return;
            }
            try {
                var datasets = metaStore.listAll();
                for (var dataset : datasets) viewManager.sync(connection, dataset);
                LOGGER.info("dataset view reconcile finished, datasets={}", datasets.size());
            } finally {
                unlock(connection);
            }
        });
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            statement.setString(1, LOCK_KEY);
            var resultSet = statement.executeQuery();
            return resultSet.next() && resultSet.getBoolean(1);
        }
    }

    private void unlock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            statement.setString(1, LOCK_KEY);
            statement.executeQuery();
        } catch (SQLException e) {
            LOGGER.warn("failed to release dataset view reconcile lock", e);
        }
    }
}
