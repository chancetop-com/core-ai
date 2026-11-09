package ai.core.server;

import ai.core.server.project.MongoProjectAttributionRepository;
import ai.core.server.project.MongoProjectReportDraftStore;
import ai.core.server.project.MongoProjectStatsStore;
import ai.core.server.project.MongoProjectStore;
import ai.core.server.project.MongoProjectSubjectStore;
import ai.core.server.project.MongoProjectTargetScanRepository;
import ai.core.server.project.PgProjectAttributionRepository;
import ai.core.server.project.PgProjectReportDraftStore;
import ai.core.server.project.PgProjectStatsStore;
import ai.core.server.project.PgProjectStore;
import ai.core.server.project.PgProjectSubjectStore;
import ai.core.server.project.PgProjectTargetScanRepository;
import ai.core.server.project.ProjectStore;
import ai.core.server.project.ProjectAttributionRepository;
import ai.core.server.project.ProjectReportDraftStore;
import ai.core.server.project.ProjectStatsStore;
import ai.core.server.project.ProjectSubjectStore;
import ai.core.server.project.ProjectTargetScanRepository;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ProjectDataStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(ProjectStore.class, bind(PgProjectStore.class));
            bind(ProjectSubjectStore.class, bind(PgProjectSubjectStore.class));
            bind(ProjectAttributionRepository.class, bind(PgProjectAttributionRepository.class));
            bind(ProjectTargetScanRepository.class, bind(PgProjectTargetScanRepository.class));
            bind(ProjectReportDraftStore.class, bind(PgProjectReportDraftStore.class));
            bind(ProjectStatsStore.class, bind(PgProjectStatsStore.class));
        } else {
            bind(ProjectStore.class, bind(MongoProjectStore.class));
            bind(ProjectSubjectStore.class, bind(MongoProjectSubjectStore.class));
            bind(ProjectAttributionRepository.class, bind(MongoProjectAttributionRepository.class));
            bind(ProjectTargetScanRepository.class, bind(MongoProjectTargetScanRepository.class));
            bind(ProjectReportDraftStore.class, bind(MongoProjectReportDraftStore.class));
            bind(ProjectStatsStore.class, bind(MongoProjectStatsStore.class));
        }
    }
}
