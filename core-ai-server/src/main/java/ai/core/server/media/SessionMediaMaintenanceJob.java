package ai.core.server.media;

import ai.core.media.MediaStoreLimits;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Deletes media bytes older than the store TTL. Refs stay in conversation state and degrade to a
 * placeholder on the next request, which is the intended contract: bytes are not kept forever just
 * because a message still names them.
 *
 * @author core-ai
 */
public class SessionMediaMaintenanceJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionMediaMaintenanceJob.class);
    private static final int BATCH = 500;

    @Inject
    SessionAttachmentRefRepository repository;
    @Inject
    BlobSessionMediaStore mediaStore;

    @Override
    public void execute(JobContext context) {
        List<SessionAttachmentRef> expired = repository.findExpiredMedia(ZonedDateTime.now().minus(MediaStoreLimits.TTL), BATCH);
        if (expired.isEmpty()) return;
        var deleted = 0;
        for (var reference : expired) {
            if (mediaStore.deleteExpired(reference)) deleted++;
        }
        if (deleted > 0) LOGGER.info("deleted {} expired session media objects", deleted);
    }
}
