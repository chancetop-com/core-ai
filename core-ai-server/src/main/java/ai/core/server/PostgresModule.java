package ai.core.server;

import ai.core.server.apiuser.PgApiKeyRow;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.project.PgProjectCountRow;
import ai.core.server.project.PgProjectRow;
import ai.core.server.project.PgSubjectFileStatsRow;
import ai.core.server.skill.PgSkillCountRow;
import ai.core.server.share.PgSessionShareRow;
import ai.core.server.user.PgUserIdRow;
import ai.core.server.user.PgUserRow;
import core.framework.module.Module;

/**
 * Configures the shared PostgreSQL database before any module binds a PostgreSQL store. Dataset and
 * native stores share one database; each store owns its tables and SQL.
 *
 * @author stephen
 */
public class PostgresModule extends Module {
    @Override
    protected void initialize() {
        var storeMode = property("sys.store.mode").orElse("mongo");
        var datasetStore = property("sys.dataset.store").orElse("mongo");
        var migrationEnabled = Boolean.parseBoolean(property("sys.dataset.migration.enabled").orElse("false"));
        if (!migrationEnabled && !"pg".equals(storeMode) && !"postgresql".equals(datasetStore)) return;

        var url = requiredProperty("sys.db.url");
        var user = requiredProperty("sys.db.user");
        var password = requiredProperty("sys.db.password");
        db().url(url);
        db().user(user);
        db().password(password);
        db().view(PgDocumentRow.class);
        db().view(PgSessionShareRow.class);
        db().view(ai.core.server.session.PgChatSessionRow.class);
        db().view(ai.core.server.session.PgChatSessionCountRow.class);
        db().view(ai.core.server.session.PgChatSessionMigrationLockRow.class);
        db().view(ai.core.server.session.PgChatSessionMigrationMarkerRow.class);
        db().view(ai.core.server.session.PgChatMessageRow.class);
        db().view(ai.core.server.session.PgChatMessageCountRow.class);
        db().view(ai.core.server.session.PgChatMessageSeqRow.class);
        db().view(ai.core.server.session.PgChatMessageMigrationLockRow.class);
        db().view(ai.core.server.session.PgChatMessageMigrationMarkerRow.class);
        db().view(ai.core.server.file.PgFileRecordRow.class);
        db().view(ai.core.server.file.PgFileRecordCountRow.class);
        db().view(ai.core.server.file.PgFileRecordUserRow.class);
        db().view(ai.core.server.file.PgFileMigrationLockRow.class);
        db().view(ai.core.server.file.PgFileMigrationMarkerRow.class);
        db().view(ai.core.server.small.PgSmallMigrationLockRow.class);
        db().view(ai.core.server.small.PgSmallMigrationMarkerRow.class);
        db().view(ai.core.server.web.foryou.PgForYouCountRow.class);
        registerAgentViews();
        registerRuntimeViews();
        db().view(PgApiKeyRow.class);
        db().view(PgProjectRow.class);
        db().view(PgProjectCountRow.class);
        db().view(PgSubjectFileStatsRow.class);
        db().view(PgSkillCountRow.class);
        db().view(PgUserRow.class);
        db().view(PgUserIdRow.class);
    }

    private void registerAgentViews() {
        db().view(ai.core.server.agent.PgAgentDefinitionRow.class);
        db().view(ai.core.server.agent.PgAgentCountRow.class);
        db().view(ai.core.server.agent.PgAgentMigrationLockRow.class);
        db().view(ai.core.server.agent.PgAgentMigrationMarkerRow.class);
        db().view(ai.core.server.comparison.PgAgentVersionRow.class);
        db().view(ai.core.server.comparison.PgAgentVersionCountRow.class);
        db().view(ai.core.server.comparison.PgAgentVersionMigrationLockRow.class);
        db().view(ai.core.server.comparison.PgAgentVersionMigrationMarkerRow.class);
        db().view(ai.core.server.schedule.PgAgentScheduleRow.class);
        db().view(ai.core.server.schedule.PgAgentScheduleMigrationLockRow.class);
        db().view(ai.core.server.schedule.PgAgentScheduleMigrationMarkerRow.class);
        db().view(ai.core.server.schedule.PgSessionScheduleRow.class);
        db().view(ai.core.server.schedule.PgSessionScheduleCountRow.class);
        db().view(ai.core.server.schedule.PgSessionScheduleMigrationLockRow.class);
        db().view(ai.core.server.schedule.PgSessionScheduleMigrationMarkerRow.class);
        db().view(ai.core.server.task.PgBackgroundTaskRow.class);
        db().view(ai.core.server.task.PgBackgroundTaskMigrationLockRow.class);
        db().view(ai.core.server.task.PgBackgroundTaskMigrationMarkerRow.class);
        db().view(ai.core.server.asynctask.PgAsyncTaskRow.class);
        db().view(ai.core.server.asynctask.PgAsyncTaskMigrationLockRow.class);
        db().view(ai.core.server.asynctask.PgAsyncTaskMigrationMarkerRow.class);
    }

    private void registerRuntimeViews() {
        db().view(ai.core.server.notification.PgNotificationRow.class);
        db().view(ai.core.server.notification.PgNotificationCountRow.class);
        db().view(ai.core.server.notification.PgNotificationMigrationLockRow.class);
        db().view(ai.core.server.notification.PgNotificationMigrationMarkerRow.class);
        db().view(ai.core.server.costalert.PgCostAlertRuleRow.class);
        db().view(ai.core.server.costalert.PgCostAlertEventRow.class);
        db().view(ai.core.server.costalert.PgCostAlertMigrationLockRow.class);
        db().view(ai.core.server.costalert.PgCostAlertMigrationMarkerRow.class);
        db().view(ai.core.server.sandbox.snapshot.PgSandboxSnapshotRow.class);
        db().view(ai.core.server.sandbox.snapshot.PgSandboxEpochRow.class);
        db().view(ai.core.server.sandbox.snapshot.PgSandboxSnapshotMigrationLockRow.class);
        db().view(ai.core.server.sandbox.snapshot.PgSandboxSnapshotMigrationMarkerRow.class);
        db().view(ai.core.server.gateway.PgMediaJobRow.class);
        db().view(ai.core.server.gateway.PgMediaJobCountRow.class);
        db().view(ai.core.server.gateway.PgMediaJobMigrationLockRow.class);
        db().view(ai.core.server.gateway.PgMediaJobMigrationMarkerRow.class);
        db().view(ai.core.server.hub.PgHubCallRow.class);
        db().view(ai.core.server.hub.PgHubCallCountRow.class);
        db().view(ai.core.server.hub.PgHubCallMigrationLockRow.class);
        db().view(ai.core.server.hub.PgHubCallMigrationMarkerRow.class);
        db().view(ai.core.server.run.PgAgentRunRow.class);
        db().view(ai.core.server.run.PgAgentRunCountRow.class);
        db().view(ai.core.server.run.PgAgentRunMigrationLockRow.class);
        db().view(ai.core.server.run.PgAgentRunMigrationMarkerRow.class);
        db().view(ai.core.server.memory.PgAgentMemoryRow.class);
        db().view(ai.core.server.memory.PgAgentMemoryAgentRow.class);
        db().view(ai.core.server.memory.PgAgentMemoryCursorRow.class);
        db().view(ai.core.server.memory.PgAgentMemoryMigrationLockRow.class);
        db().view(ai.core.server.memory.PgAgentMemoryMigrationMarkerRow.class);
        db().view(ai.core.server.memory.experiment.PgMemoryExperimentConfigRow.class);
        db().view(ai.core.server.memory.experiment.PgMemoryExperimentRunRow.class);
        db().view(ai.core.server.memory.experiment.PgMemoryExperimentCountRow.class);
        db().view(ai.core.server.comparison.PgAgentComparisonRow.class);
        db().view(ai.core.server.comparison.PgComparisonVoteRow.class);
        db().view(ai.core.server.comparison.PgComparisonVoteCountRow.class);
        db().view(ai.core.server.comparison.PgComparisonMigrationLockRow.class);
        db().view(ai.core.server.comparison.PgComparisonMigrationMarkerRow.class);
        registerWorkflowViews();
        registerReplayViews();
        registerSpanViews();
    }

    private void registerSpanViews() {
        db().view(ai.core.server.trace.PgSpanRow.class);
        db().view(ai.core.server.trace.PgTraceCountRow.class);
        db().view(ai.core.server.trace.PgTraceIdRow.class);
        db().view(ai.core.server.trace.PgSpanMigrationLockRow.class);
        db().view(ai.core.server.trace.PgSpanMigrationMarkerRow.class);
        db().view(ai.core.server.trace.PgMigrationProgressRow.class);
        db().view(ai.core.server.trace.PgTraceRow.class);
        db().view(ai.core.server.trace.PgTraceFacetRow.class);
        db().view(ai.core.server.trace.PgTraceAggregateRow.class);
        db().view(ai.core.server.trace.PgTraceTraceIdRow.class);
        db().view(ai.core.server.trace.PgTraceRollupRow.class);
        db().view(ai.core.server.trace.PgTraceStatsRow.class);
        db().view(ai.core.server.trace.PgTraceTrendRow.class);
        db().view(ai.core.server.trace.PgTraceDimensionTrendRow.class);
        db().view(ai.core.server.trace.PgTraceDailyUsageRow.class);
        db().view(ai.core.server.trace.service.PgTracePayloadRow.class);
        db().view(ai.core.server.runtime.PgRuntimeSampleRow.class);
    }

    private void registerWorkflowViews() {
        db().view(ai.core.server.workflow.PgWorkflowDefinitionRow.class);
        db().view(ai.core.server.workflow.PgWorkflowDefinitionCountRow.class);
        db().view(ai.core.server.workflow.PgWorkflowVersionRow.class);
        db().view(ai.core.server.workflow.PgWorkflowRunRow.class);
        db().view(ai.core.server.workflow.PgWorkflowRunCountRow.class);
        db().view(ai.core.server.workflow.PgWorkflowNodeRunRow.class);
        db().view(ai.core.server.workflow.PgWorkflowMigrationLockRow.class);
        db().view(ai.core.server.workflow.PgWorkflowMigrationMarkerRow.class);
    }

    private void registerReplayViews() {
        db().view(ai.core.server.replay.PgReplayExperimentRow.class);
        db().view(ai.core.server.replay.PgReplayExperimentCountRow.class);
        db().view(ai.core.server.replay.PgReplayRunRow.class);
        db().view(ai.core.server.replay.PgReplayMigrationLockRow.class);
        db().view(ai.core.server.replay.PgReplayMigrationMarkerRow.class);
    }
}










