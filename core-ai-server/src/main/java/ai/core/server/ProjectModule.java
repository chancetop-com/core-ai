package ai.core.server;

import ai.core.api.server.project.ProjectWebService;
import ai.core.server.project.ProjectAnalysisJob;
import ai.core.server.project.ProjectAnalysisService;
import ai.core.server.project.ProjectAttributionJob;
import ai.core.server.project.ProjectAttributionStage;
import ai.core.server.project.ProjectMemberQueryService;
import ai.core.server.project.ProjectQueryService;
import ai.core.server.project.ProjectReportCompletionJob;
import ai.core.server.project.ProjectReportStage;
import ai.core.server.project.ProjectResetService;
import ai.core.server.project.ProjectService;
import ai.core.server.project.ProjectStateService;
import ai.core.server.project.ProjectStatsQueryService;
import ai.core.server.project.ProjectStatsRefreshJob;
import ai.core.server.project.ProjectSubjectAnalysisStage;
import ai.core.server.project.ProjectToolDispatcher;
import ai.core.server.project.ProjectTools;
import ai.core.server.project.ProjectViewAssembler;
import ai.core.server.project.ProjectWebServiceImpl;
import ai.core.server.project.ProjectWriterToolFactory;
import ai.core.server.project.ProjectWriterToolSupport;
import core.framework.module.Module;

import java.time.Duration;

/**
 * Project feature module. Loaded AFTER the agent/session/run modules because the analysis pipeline
 * injects AgentRunner (bound by AgentRunnerModule); no other module injects ProjectService, so the
 * early-load requirement from the binding era is gone.
 *
 * @author stephen
 */
public class ProjectModule extends Module {
    @Override
    protected void initialize() {
        // bind() resolves @Inject eagerly, so each service's dependencies must be bound first:
        // ProjectStateService before ProjectService (it injects it), QueryService before the
        // assembler/report stage, stages before the analysis service.
        bind(ProjectStateService.class);
        bind(ProjectService.class);
        bind(ProjectQueryService.class);
        bind(ProjectMemberQueryService.class);
        bind(ProjectViewAssembler.class);
        bind(ProjectStatsQueryService.class);
        bind(ProjectAttributionStage.class);
        bind(ProjectSubjectAnalysisStage.class);
        bind(ProjectReportStage.class);
        bind(ProjectResetService.class);
        bind(ProjectToolDispatcher.class);
        bind(ProjectAnalysisService.class);
        api().service(ProjectWebService.class, bind(ProjectWebServiceImpl.class));
        var projectTools = bind(ProjectTools.class);
        projectTools.initialize();
        ProjectWriterToolSupport.setFactory(bind(ProjectWriterToolFactory.class));
        // high-frequency attribution (tags new member material) + low-frequency subject analysis
        schedule().fixedRate("project-attribution", bind(ProjectAttributionJob.class), Duration.ofMinutes(10));
        schedule().fixedRate("project-analysis", bind(ProjectAnalysisJob.class), Duration.ofMinutes(60));
        // cached cost snapshot recomputation
        schedule().fixedRate("project-stats-refresh", bind(ProjectStatsRefreshJob.class), Duration.ofMinutes(10));
        // in-flight report render completion (the renderer agent writes sections into a draft)
        schedule().fixedRate("project-report-completion", bind(ProjectReportCompletionJob.class), Duration.ofSeconds(30));
    }
}
