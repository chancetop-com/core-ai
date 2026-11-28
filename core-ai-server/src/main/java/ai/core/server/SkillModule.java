package ai.core.server;

import ai.core.api.server.SkillQualityAdminWebService;
import ai.core.api.server.SkillWebService;
import ai.core.server.skill.MarketplaceService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.PresetSkillSeeder;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillBlobStore;
import ai.core.server.skill.SkillPublishService;
import ai.core.server.skill.SkillQualityFixtureBuilder;
import ai.core.server.skill.SkillRepoSyncJob;
import ai.core.server.skill.SkillSdkAssignmentPlanService;
import ai.core.server.skill.SkillService;
import ai.core.server.skill.SkillTierAdminService;
import ai.core.server.skill.SkillToolAssembler;
import ai.core.server.skill.SkillUploadController;
import ai.core.server.skill.quality.SkillQualityAdminService;
import ai.core.server.skill.telemetry.SkillTelemetryBuffer;
import ai.core.server.skill.telemetry.SkillTelemetryCleanupJob;
import ai.core.server.skill.telemetry.SkillTelemetryFlushJob;
import ai.core.server.skill.telemetry.SkillTelemetryService;
import ai.core.server.skillhub.SkillHubAccessPolicy;
import ai.core.server.web.SkillQualityAdminWebServiceImpl;
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
        bind(SkillBlobStore.class);
        bind(SkillPublishService.class);
        bind(SkillService.class);
        bind(SkillSdkAssignmentPlanService.class);
        bind(SkillQualityFixtureBuilder.class);
        bind(MarketplaceService.class);
        bind(MongoSkillProvider.class);
        bind(SkillArchiveBuilder.class);
        bind(SkillToolAssembler.class);
        bind(SkillHubAccessPolicy.class);
        bind(SkillTelemetryBuffer.class);
        bind(SkillTelemetryService.class);
        api().service(SkillWebService.class, bind(SkillWebServiceImpl.class));
        var tierAdmin = bind(SkillTierAdminService.class);
        tierAdmin.setMaxAlwaysOnSkills(intProperty("sys.skill.alwaysOn.max",
            SkillTierAdminService.DEFAULT_MAX_ALWAYS_ON_SKILLS));
        bind(SkillQualityAdminService.class);
        api().service(SkillQualityAdminWebService.class, bind(SkillQualityAdminWebServiceImpl.class));
        http().route(HTTPMethod.POST, "/api/skills/upload", bind(SkillUploadController.class));
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            var seeder = bind(PresetSkillSeeder.class);
            onStartup(seeder::seed);
        }
        schedule().fixedRate("skill-repo-sync", bind(SkillRepoSyncJob.class),
            Duration.ofMinutes(SkillRepoSyncJob.TICK_MINUTES));
        schedule().fixedRate("skill-telemetry-flush", bind(SkillTelemetryFlushJob.class), Duration.ofSeconds(10));
        schedule().fixedRate("skill-telemetry-cleanup", bind(SkillTelemetryCleanupJob.class), Duration.ofHours(1));
    }

    private int intProperty(String key, int defaultValue) {
        var value = property(key).orElse(null);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
