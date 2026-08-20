package ai.core.server.workflow;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.Notification;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.server.domain.SystemSettings;
import ai.core.server.domain.User;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.notification.NotificationEventPublisher;
import ai.core.server.notification.NotificationService;
import ai.core.server.project.ProjectService;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.session.SessionRegistry;
import ai.core.server.skill.SkillService;
import ai.core.server.workflow.executor.EndExecutor;
import ai.core.server.workflow.executor.HumanInputExecutor;
import ai.core.server.workflow.executor.StartExecutor;
import ai.core.server.workflow.executor.WorkflowExecutor;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import core.framework.mongo.module.MongoConfig;
import core.framework.test.module.AbstractTestModule;

import java.util.Map;

/**
 * Minimal integration-test context: real Mongo + just the workflow beans needed to drive a START -> END run.
 * No agent gateway / LLM / MCP / sandbox, so it boots fast against a local Mongo (mongodb://localhost:27017).
 *
 * @author Xander
 */
public class WorkflowTestModule extends AbstractTestModule {
    @Override
    protected void initialize() {
        var mongo = config(MongoConfig.class);
        mongo.uri("mongodb://localhost:27017/wftest");
        mongo.collection(WorkflowDefinition.class);
        mongo.collection(WorkflowPublishedVersion.class);
        mongo.collection(WorkflowRun.class);
        mongo.collection(WorkflowNodeRun.class);
        mongo.collection(AgentDefinition.class);   // injected by Workflow Agent services; unused for START -> END
        mongo.collection(SkillDefinition.class);   // validates owner-editable Agent skill dependencies during capture
        mongo.collection(ToolRegistryEntry.class);      // injected by WorkflowPortService for MCP reference resolution
        mongo.collection(Notification.class);            // injected by NotificationService (bound below)
        mongo.collection(ChatSession.class);             // shared by SessionRegistry Mongo integration tests
        mongo.collection(Project.class);                 // project feature: SessionRegistry/WorkflowRunService injections
        mongo.collection(ProjectSubject.class);
        mongo.collection(ProjectSubjectAttribution.class);
        mongo.collection(ProjectSubjectEvent.class);
        mongo.collection(User.class);                    // project feature: ProjectService/ProjectAccess permission checks
        mongo.collection(SystemSettings.class);          // project feature: RoleRegistry permission resolution
        mongo.collection(AgentRun.class);
        mongo.collection(FileRecord.class);
        mongo.view(ToolRef.class);                        // partial updates persist loaded ToolRef values

        bindWorkflowServices();

        // The test connects to a real Mongo (localhost:27017) which has notablescan=1,
        // so every query must be covered by an index. These indexes are normally created
        // by schema migrations in the main app database but are not applied to wftest.
        context.startupHook.initialize.add(() -> {
            var mongoBean = this.<Mongo>bean(Mongo.class);
            // workflow_definitions
            mongoBean.createIndex("workflow_definitions", Indexes.ascending("user_id"));
            mongoBean.createIndex("workflow_definitions", Indexes.ascending("published_version_id"));
            mongoBean.createIndex("workflow_definitions", Indexes.compoundIndex(
                Indexes.ascending("visibility"), Indexes.ascending("status"), Indexes.ascending("published_version_id")));
            // workflow_published_versions
            mongoBean.createIndex("workflow_published_versions", Indexes.ascending("workflow_id"));
            mongoBean.createIndex("workflow_published_versions", Indexes.compoundIndex(
                Indexes.ascending("workflow_id"), Indexes.ascending("preview"), Indexes.ascending("status")));
            // workflow_runs
            mongoBean.createIndex("workflow_runs", Indexes.ascending("workflow_id"));
            mongoBean.createIndex("workflow_runs", Indexes.ascending("user_id"));
            mongoBean.createIndex("workflow_runs", Indexes.ascending("visibility"));
            mongoBean.createIndex("workflow_runs", Indexes.ascending("parent_run_id"));
            mongoBean.createIndex("workflow_runs", Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("lease_until")));
            mongoBean.createIndex("workflow_runs", Indexes.descending("created_at"));
            // workflow_node_runs
            mongoBean.createIndex("workflow_node_runs", Indexes.ascending("run_id"));
            mongoBean.createIndex("workflow_node_runs", Indexes.compoundIndex(
                Indexes.ascending("run_id"), Indexes.ascending("node_id"), Indexes.ascending("scope_path_key")));
            mongoBean.createIndex("workflow_node_runs", Indexes.ascending("child_run_id"));
            // agents
            mongoBean.createIndex("agents", Indexes.compoundIndex(
                Indexes.ascending("user_id"), Indexes.ascending("type"),
                Indexes.ascending("name_key"), Indexes.ascending("_id")));
            mongoBean.createIndex("agents", Indexes.compoundIndex(
                Indexes.ascending("status"), Indexes.ascending("type"),
                Indexes.ascending("name_key"), Indexes.ascending("_id")));
        });
    }

    private void bindWorkflowServices() {
        // ProjectService's permission deps must be bound BEFORE it (core-ng bind() resolves eagerly)
        bind(ai.core.server.rbac.RoleRegistry.class);
        bind(ai.core.server.apiuser.PermissionService.class);
        bind(ai.core.server.project.ProjectStateService.class);   // injected by ProjectService
        bind(ProjectService.class);          // SessionRegistry/WorkflowRunService inject it eagerly at bind() time
        bind(SessionRegistry.class);
        bind(WorkflowDefinitionService.class);
        bind(WorkflowAgentOptionService.class);
        bind(WorkflowPortService.class);           // import/export; shares this module so it never adds a second test context
        bind(SkillService.class);
        bind(WorkflowAgentSnapshotService.class);
        bind(WorkflowPrivateAgentSafetyValidator.class);
        bind(WorkflowPublishService.class);
        // bind the graph-loader seam before WorkflowRunService: the framework injects eagerly at bind() time, so a
        // dependency must already be registered when its dependent is bound.
        var graphLoader = bind(MongoWorkflowGraphLoader.class);
        bind(WorkflowGraphLoader.class, graphLoader);
        bind(WorkflowRunService.class);
        // bind the sub-workflow gateway before the executor/runner that depend on it because injection is eager
        var workflowRunGateway = bind(MongoWorkflowRunGateway.class);
        bind(WorkflowRunGateway.class, workflowRunGateway);
        var registry = new NodeExecutorRegistry(Map.of(
            NodeType.START, new StartExecutor(),
            NodeType.END, new EndExecutor(),
            NodeType.HUMAN_INPUT, new HumanInputExecutor(),
            NodeType.WORKFLOW, new WorkflowExecutor(workflowRunGateway, 5)));
        bind(NodeExecutor.class, registry);
        bind(new SandboxService());   // disabled (enabled=false) — releaseSandbox no-ops; START -> END runs no CODE node
        bind(new NotificationEventPublisher());
        bind(NotificationService.class);
        bind(WorkflowRunner.class);
        bind(WorkflowRunnerJob.class);   // claim sweep + stranded-PAUSED recovery, exercised by the resume tests
    }
}
