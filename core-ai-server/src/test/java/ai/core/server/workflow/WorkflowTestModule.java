package ai.core.server.workflow;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRegistry;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.workflow.executor.EndExecutor;
import ai.core.server.workflow.executor.StartExecutor;
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
        mongo.collection(AgentDefinition.class);   // injected by WorkflowPublishService; unused for START -> END
        mongo.collection(ToolRegistry.class);      // injected by WorkflowPortService for MCP reference resolution

        bind(WorkflowDefinitionService.class);
        bind(WorkflowPortService.class);           // import/export; shares this module so it never adds a second test context
        bind(WorkflowPublishService.class);
        // bind the graph-loader seam before WorkflowRunService: the framework injects eagerly at bind() time, so a
        // dependency must already be registered when its dependent is bound (matches ServerModule's ordering).
        var graphLoader = bind(MongoWorkflowGraphLoader.class);
        bind(WorkflowGraphLoader.class, graphLoader);
        bind(WorkflowRunService.class);
        var registry = new NodeExecutorRegistry(Map.of(
            NodeType.START, new StartExecutor(),
            NodeType.END, new EndExecutor()));
        bind(NodeExecutor.class, registry);
        bind(new SandboxService());   // disabled (enabled=false) — releaseSandbox no-ops; START -> END runs no CODE node
        bind(WorkflowRunner.class);
    }
}
