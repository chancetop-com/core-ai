package ai.core.server;

import ai.core.api.server.workflow.WorkflowWebService;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.web.WorkflowWebServiceImpl;
import ai.core.server.workflow.AgentRunGateway;
import ai.core.server.workflow.MongoAgentRunGateway;
import ai.core.server.workflow.MongoWorkflowGraphLoader;
import ai.core.server.workflow.MongoWorkflowRunGateway;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeExecutorRegistry;
import ai.core.server.workflow.NodeType;
import ai.core.server.workflow.WorkflowDefinitionService;
import ai.core.server.workflow.WorkflowGraphLoader;
import ai.core.server.workflow.WorkflowPortService;
import ai.core.server.workflow.WorkflowPublishService;
import ai.core.server.workflow.WorkflowRunGateway;
import ai.core.server.workflow.WorkflowRunService;
import ai.core.server.workflow.WorkflowRunner;
import ai.core.server.workflow.WorkflowRunnerJob;
import ai.core.server.workflow.executor.AgentExecutor;
import ai.core.server.workflow.executor.AggregatorExecutor;
import ai.core.server.workflow.executor.ApiToolExecutor;
import ai.core.server.workflow.executor.CodeExecutor;
import ai.core.server.workflow.executor.EndExecutor;
import ai.core.server.workflow.executor.HttpExecutor;
import ai.core.server.workflow.executor.HumanInputExecutor;
import ai.core.server.workflow.executor.IfElseExecutor;
import ai.core.server.workflow.executor.McpToolExecutor;
import ai.core.server.workflow.executor.RetryingNodeExecutor;
import ai.core.server.workflow.executor.StartExecutor;
import ai.core.server.workflow.executor.TemplateExecutor;
import ai.core.server.workflow.executor.WorkflowExecutor;
import core.framework.module.Module;

import java.time.Duration;
import java.util.Map;

/**
 * @author stephen
 */
public class WorkflowModule extends Module {

    @Override
    protected void initialize() {
        bindWorkflow(bean(ToolRegistryService.class), bean(SandboxService.class), bean(FileService.class));
        api().service(WorkflowWebService.class, bind(WorkflowWebServiceImpl.class));
    }

    private void bindWorkflow(ToolRegistryService toolRegistryService, SandboxService sandboxService, FileService fileService) {
        // AGENT/LLM nodes run as decoupled child AgentRuns through this gateway (depends on AgentRunner above).
        var agentRunGateway = bind(MongoAgentRunGateway.class);
        bind(AgentRunGateway.class, agentRunGateway);
        var graphLoader = bind(MongoWorkflowGraphLoader.class);
        bind(WorkflowGraphLoader.class, graphLoader);

        // AGENT/LLM, the two tool nodes and HTTP get synchronous retry-on-transient-failure; START/END/IF_ELSE/CODE don't.
        var agentExecutor = new RetryingNodeExecutor(new AgentExecutor(agentRunGateway));
        var mcpToolExecutor = new RetryingNodeExecutor(new McpToolExecutor(toolRegistryService));
        var apiToolExecutor = new RetryingNodeExecutor(new ApiToolExecutor(toolRegistryService));
        var httpExecutor = new RetryingNodeExecutor(new HttpExecutor());
        var workflowRunGateway = bind(MongoWorkflowRunGateway.class);
        bind(WorkflowRunGateway.class, workflowRunGateway);
        var workflowExecutor = new WorkflowExecutor(workflowRunGateway, 5);   // depth cap 5 (design default)
        var registry = new NodeExecutorRegistry(Map.ofEntries(
                Map.entry(NodeType.START, new StartExecutor()),
                Map.entry(NodeType.END, new EndExecutor()),
                Map.entry(NodeType.AGENT, agentExecutor),
                Map.entry(NodeType.LLM, agentExecutor),
                Map.entry(NodeType.MCP_TOOL, mcpToolExecutor),
                Map.entry(NodeType.API_TOOL, apiToolExecutor),
                Map.entry(NodeType.HTTP, httpExecutor),
                Map.entry(NodeType.IF_ELSE, new IfElseExecutor()),
                Map.entry(NodeType.AGGREGATOR, new AggregatorExecutor()),
                Map.entry(NodeType.TEMPLATE, new TemplateExecutor()),
                Map.entry(NodeType.HUMAN_INPUT, new HumanInputExecutor()),
                Map.entry(NodeType.WORKFLOW, workflowExecutor),
                Map.entry(NodeType.CODE, new CodeExecutor(sandboxService, fileService))));
        bind(NodeExecutor.class, registry);

        // WorkflowDefinitionService must be bound before WorkflowPortService, which injects it
        bind(WorkflowDefinitionService.class);
        bind(WorkflowPublishService.class);
        bind(WorkflowPortService.class);
        bind(WorkflowRunner.class);
        bind(WorkflowRunService.class);
        // Pure recovery/fallback: every creation path submits immediately, so this only catches hard crashes
        // (OOM/SIGKILL/node loss) that leave a stale lease — a slow tick is enough. (No shutdown-time DB hand-off:
        // a Mongo write that late in teardown fails when the codec registry is already gone.)
        schedule().fixedRate("workflow-runner", bind(WorkflowRunnerJob.class), Duration.ofSeconds(60));
    }
}
