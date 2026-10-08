package ai.core.server;

import ai.core.api.server.SkillWebService;
import ai.core.server.skill.MarketplaceService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillRepoSyncJob;
import ai.core.server.skill.SkillService;
import ai.core.server.skill.SkillToolAssembler;
import ai.core.server.skill.SkillUploadController;
import ai.core.server.web.SkillWebServiceImpl;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class SkillModule extends Module {
    @Override
    protected void initialize() {
        bind(SkillService.class);
        bind(MarketplaceService.class);
        bind(MongoSkillProvider.class);
        bind(new SkillArchiveBuilder());
        bind(SkillToolAssembler.class);
        api().service(SkillWebService.class, bind(SkillWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/skills/upload", bind(SkillUploadController.class));
        // fixed wake-up tick only; the job applies the enabled switch and interval configured on the
        // system configuration page, so admins can throttle or stop the sync without a restart
        schedule().fixedRate("skill-repo-sync", bind(SkillRepoSyncJob.class), Duration.ofMinutes(SkillRepoSyncJob.TICK_MINUTES));
    }
}
