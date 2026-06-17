package ai.core.server.workflow;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRegistry;
import ai.core.server.domain.WorkflowDefinition;
import core.framework.mongo.module.MongoConfig;
import core.framework.test.module.AbstractTestModule;

/**
 * Minimal context for WorkflowPortService: real Mongo + the three collections it touches and the definition
 * service. No runner/executors needed — port operations never run a workflow.
 *
 * @author Xander
 */
public class WorkflowPortTestModule extends AbstractTestModule {
    @Override
    protected void initialize() {
        var mongo = config(MongoConfig.class);
        mongo.uri("mongodb://localhost:27017/wfporttest");
        mongo.collection(WorkflowDefinition.class);
        mongo.collection(AgentDefinition.class);
        mongo.collection(ToolRegistry.class);

        bind(WorkflowDefinitionService.class);
        bind(WorkflowPortService.class);
    }
}
