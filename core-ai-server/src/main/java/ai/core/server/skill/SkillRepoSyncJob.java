package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.settings.SystemSettingsService;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;

/**
 * Keeps REPO-sourced skills current so hub consumers never see a stale catalog.
 * <p>
 * The scheduler wakes this job every {@link #TICK_MINUTES} minutes and the job decides whether a sweep is due, because
 * admins can disable it or change the interval on the system configuration page — read per run, so the change applies
 * without a restart. The tick only bounds how quickly a setting change takes effect.
 *
 * @author stephen
 */
public class SkillRepoSyncJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillRepoSyncJob.class);
    private static final int MAX_ERROR_LENGTH = 500;
    private static final int DUE_TOLERANCE_SECONDS = 30;
    public static final int TICK_MINUTES = 5;
    public static final int DEFAULT_SYNC_INTERVAL_MINUTES = 30;

    private static String describe(Throwable error) {
        var message = new StringBuilder();
        for (Throwable current = error; current != null && message.length() < MAX_ERROR_LENGTH; current = current.getCause()) {
            if (!message.isEmpty()) message.append(": ");
            message.append(current.getMessage());
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) + "..." : message.toString();
    }

    @Inject
    SkillService skillService;

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

    @Inject
    SystemSettingsService systemSettingsService;

    private final SkillRepoSyncBackoff backoff = new SkillRepoSyncBackoff();

    // per server instance and reset on restart, like the backoff state
    private volatile ZonedDateTime lastSweepAt;

    @Override
    public void execute(JobContext context) {
        skillService.sweepStaleRepoTempDirs();

        if (!systemSettingsService.skillRepoSyncEnabled()) {
            LOGGER.debug("skill repo sync is disabled, skipping scheduled sweep");
            return;
        }
        if (!sweepDue(systemSettingsService.skillRepoSyncIntervalMinutes())) {
            return;
        }
        lastSweepAt = ZonedDateTime.now();

        var repoSkills = skillCollection.find(Filters.eq("source_type", SkillSourceType.REPO));
        int failed = 0;
        int deferred = 0;
        for (var skill : repoSkills) {
            String repoUrl = skill.repoConfig == null ? null : skill.repoConfig.repoUrl;
            if (backoff.deferred(repoUrl)) {
                deferred++;
                continue;
            }
            try {
                skillService.syncFromRepoIfChanged(skill.id);
                backoff.succeeded(repoUrl);
            } catch (Exception e) {
                failed++;
                backoff.failed(repoUrl);
                LOGGER.warn("failed to sync repo skill, id={}, qualifiedName={}, error={}", skill.id, skill.qualifiedName, describe(e));
            }
        }
        if (failed > 0 || deferred > 0) {
            LOGGER.info("skill repo sync finished, skills={}, failed={}, deferred={}", repoSkills.size(), failed, deferred);
        }
    }

    private boolean sweepDue(int intervalMinutes) {
        var last = lastSweepAt;
        if (last == null) return true;
        // a check can land a moment before the exact deadline when the interval is a multiple of the tick;
        // without the tolerance the sweep would randomly slip a whole tick
        return !ZonedDateTime.now().plusSeconds(DUE_TOLERANCE_SECONDS).isBefore(last.plusMinutes(intervalMinutes));
    }
}
