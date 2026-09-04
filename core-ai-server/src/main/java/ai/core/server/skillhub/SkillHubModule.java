package ai.core.server.skillhub;

import ai.core.api.server.SkillHubWebService;
import ai.core.server.skill.SkillService;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import java.time.Duration;

/**
 * Skill Hub surface: scoring search, bare-name lookup, content and ZIP archive access
 * over the shared skill registry, without touching the management CRUD/upload surface.
 * Loaded right after {@code SkillModule} so the catalog invalidator can be wired to
 * {@link SkillService} write paths.
 *
 * @author stephen
 */
public class SkillHubModule extends Module {
    @Override
    protected void initialize() {
        var catalog = bind(SkillCatalogService.class);
        var skillService = bean(SkillService.class);
        onStartup(() -> skillService.setCatalogInvalidator(catalog::invalidate));

        bind(SkillHubAccessPolicy.class);
        bind(SkillHubService.class);

        api().service(SkillHubWebService.class, bind(SkillHubWebServiceImpl.class));
        http().route(HTTPMethod.GET, "/api/hub/skills/:namespace/:name/archive", bind(SkillArchiveController.class));
        schedule().fixedRate("skill-hub-catalog-sync", bind(SkillHubCatalogSyncJob.class), Duration.ofSeconds(30));
    }
}
