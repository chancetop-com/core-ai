package ai.core.server.state;

import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expired rows are already invisible to readers; this only keeps the tables from growing forever.
 * Every pod may run the delete, row locks make the statements safe to repeat.
 *
 * @author stephen
 */
public class StateExpiryCleanupJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(StateExpiryCleanupJob.class);

    @Inject
    Database database;

    @Override
    public void execute(JobContext context) {
        var sessionActivity = database.execute("DELETE FROM messaging.session_activity WHERE expires_at < now()");
        var a2aTasks = database.execute("DELETE FROM messaging.a2a_tasks WHERE expires_at < now()");
        if (sessionActivity > 0 || a2aTasks > 0) {
            LOGGER.info("expired state removed, sessionActivity={}, a2aTasks={}", sessionActivity, a2aTasks);
        }
    }
}
