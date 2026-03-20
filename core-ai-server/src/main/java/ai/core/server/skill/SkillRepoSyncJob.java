package ai.core.server.skill;

import ai.core.server.domain.SkillSourceType;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import ai.core.server.domain.SkillDefinition;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class SkillRepoSyncJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillRepoSyncJob.class);

    @Inject
    SkillService skillService;

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

    @Override
    public void execute(JobContext context) {
        var repoSkills = skillCollection.find(Filters.eq("source_type", SkillSourceType.REPO));

        for (var skill : repoSkills) {
            try {
                skillService.syncFromRepo(skill.id);
            } catch (Exception e) {
                LOGGER.warn("failed to sync repo skill, id={}, qualifiedName={}", skill.id, skill.qualifiedName, e);
            }
        }
    }
}
