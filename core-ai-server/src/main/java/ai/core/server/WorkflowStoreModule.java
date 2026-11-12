package ai.core.server;

import ai.core.server.workflow.MongoWorkflowDefinitionStore;
import ai.core.server.workflow.MongoWorkflowNodeRunStore;
import ai.core.server.workflow.MongoWorkflowRunStore;
import ai.core.server.workflow.MongoWorkflowVersionStore;
import ai.core.server.workflow.PgWorkflowDefinitionStore;
import ai.core.server.workflow.PgWorkflowNodeRunStore;
import ai.core.server.workflow.PgWorkflowRunStore;
import ai.core.server.workflow.PgWorkflowVersionStore;
import ai.core.server.workflow.WorkflowDefinitionStore;
import ai.core.server.workflow.WorkflowMigrationService;
import ai.core.server.workflow.WorkflowNodeRunStore;
import ai.core.server.workflow.WorkflowRunStore;
import ai.core.server.workflow.WorkflowVersionStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class WorkflowStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(WorkflowDefinitionStore.class, bind(PgWorkflowDefinitionStore.class));
            bind(WorkflowVersionStore.class, bind(PgWorkflowVersionStore.class));
            bind(WorkflowRunStore.class, bind(PgWorkflowRunStore.class));
            bind(WorkflowNodeRunStore.class, bind(PgWorkflowNodeRunStore.class));
            var migration = bind(WorkflowMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(WorkflowDefinitionStore.class, bind(MongoWorkflowDefinitionStore.class));
            bind(WorkflowVersionStore.class, bind(MongoWorkflowVersionStore.class));
            bind(WorkflowRunStore.class, bind(MongoWorkflowRunStore.class));
            bind(WorkflowNodeRunStore.class, bind(MongoWorkflowNodeRunStore.class));
        }
    }
}
