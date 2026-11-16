package ai.core.server.messaging;

import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes leases and counters whose owner pod stopped heartbeating. Readers already ignore those
 * rows; this only stops the tables from growing and lets dead pods disappear from the registry.
 * Every delete is idempotent, so each pod may run it.
 *
 * @author stephen
 */
public class LeaseCleanupJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(LeaseCleanupJob.class);
    private static final int DEAD_POD_RETENTION_SECONDS = 300;

    @Inject
    Database database;

    @Override
    public void execute(JobContext context) {
        var ownership = database.execute("DELETE FROM messaging.session_ownership o "
            + "WHERE NOT EXISTS (SELECT 1 FROM messaging.pods p WHERE p.pod_id = o.owner_pod "
            + "AND p.heartbeat_at > now() - make_interval(secs => ?))", PgOwnershipStore.LIVENESS_SECONDS);
        var turns = database.execute("DELETE FROM messaging.turn_states t "
            + "WHERE NOT EXISTS (SELECT 1 FROM messaging.pods p WHERE p.pod_id = t.owner_pod "
            + "AND p.heartbeat_at > now() - make_interval(secs => ?))", PgOwnershipStore.LIVENESS_SECONDS);
        var counts = database.execute("DELETE FROM messaging.user_session_counts c "
            + "WHERE NOT EXISTS (SELECT 1 FROM messaging.pods p WHERE p.pod_id = c.pod_id "
            + "AND p.heartbeat_at > now() - make_interval(secs => ?))", PgOwnershipStore.LIVENESS_SECONDS);
        var pods = database.execute("DELETE FROM messaging.pods WHERE heartbeat_at < now() - make_interval(secs => ?)",
            DEAD_POD_RETENTION_SECONDS);
        if (ownership > 0 || turns > 0 || counts > 0 || pods > 0) {
            LOGGER.info("dead pod state removed, ownership={}, turns={}, sessionCounts={}, pods={}",
                ownership, turns, counts, pods);
        }
    }
}
