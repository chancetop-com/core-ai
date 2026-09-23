package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class SkillRepoSyncJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillRepoSyncJob.class);
    private static final int MAX_ERROR_LENGTH = 500;

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

    private final SkillRepoSyncBackoff backoff = new SkillRepoSyncBackoff();

    @Override
    public void execute(JobContext context) {
        skillService.sweepStaleRepoTempDirs();

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
}
