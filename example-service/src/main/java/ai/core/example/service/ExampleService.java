package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.agent.AgentGroup;
import ai.core.agent.ExecutionContext;
import ai.core.agent.handoff.HandoffType;
import ai.core.agent.NodeStatus;
import ai.core.agent.UserInputAgent;
import ai.core.agent.listener.listeners.DefaultAgentRunningEventListener;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.defaultagents.DefaultSummaryAgent;
import ai.core.example.api.example.MCPToolCallRequest;
import ai.core.example.api.example.OrderIssueResponse;
import ai.core.flow.Flow;
import ai.core.flow.FlowEdge;
import ai.core.flow.FlowNode;
import ai.core.flow.edges.ConnectionEdge;
import ai.core.flow.edges.SettingEdge;
import ai.core.flow.nodes.AgentFlowNode;
import ai.core.flow.nodes.AgentGroupFlowNode;
import ai.core.flow.nodes.DeepSeekFlowNode;
import ai.core.flow.nodes.EmptyFlowNode;
import ai.core.flow.nodes.HybridHandoffFlowNode;
import ai.core.flow.nodes.OperatorSwitchFlowNode;
import ai.core.flow.nodes.ThrowErrorFlowNode;
import ai.core.flow.nodes.WebhookTriggerFlowNode;
import ai.core.flow.nodes.builtinnodes.BuiltinModeratorAgentFlowNode;
import ai.core.flow.nodes.builtinnodes.BuiltinPythonAgentFlowNode;
import ai.core.flow.nodes.builtinnodes.BuiltinPythonToolFlowNode;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientService;
import ai.core.mcp.client.McpClientServerConfig;
import ai.core.persistence.providers.TemporaryPersistenceProvider;
import ai.core.tool.function.Functions;
import ai.core.tool.mcp.McpToolCalls;
import com.google.common.collect.Lists;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class ExampleService {
    private final Logger logger = LoggerFactory.getLogger(ExampleService.class);
    @Inject
    TemporaryPersistenceProvider persistenceProvider;
    @Inject
    WeatherService weatherService;
    @Inject
    UserInfoService userInfoService;
    @Inject
    LLMProviders llmProviders;

    public String flow(String query) {
        var nodeTrigger = new WebhookTriggerFlowNode(UUID.randomUUID().toString(), "Webhook", "https://localhost/webhook");
        var nodeAgent = new AgentFlowNode(UUID.randomUUID().toString(), "Agent");
        var nodeSwitch = new OperatorSwitchFlowNode(UUID.randomUUID().toString(), "Switch");
        var nodeEmpty = new EmptyFlowNode(UUID.randomUUID().toString(), "Empty");
        var nodeError = new ThrowErrorFlowNode(UUID.randomUUID().toString(), "Error", "test throw error node");
        var edgeTrigger = new ConnectionEdge(UUID.randomUUID().toString()).connect(nodeTrigger.getId(), nodeAgent.getId());
        var edgeSwitch1 = new ConnectionEdge(UUID.randomUUID().toString(), "1").connect(nodeSwitch.getId(), nodeError.getId());
        var edgeSwitch2 = new ConnectionEdge(UUID.randomUUID().toString(), "0").connect(nodeSwitch.getId(), nodeEmpty.getId());
        var nodeDeepSeek = new DeepSeekFlowNode(UUID.randomUUID().toString(), "DeepSeek");
        var edgeDeepSeek = new SettingEdge(UUID.randomUUID().toString()).connect(nodeAgent.getId(), nodeDeepSeek.getId());
        nodeAgent.setSystemPrompt("Your are a calculator, you can do any math calculation.\nDon't output your analysis or thinking process, only output the question and answer.");
        List<FlowNode<?>> nodes = Lists.newArrayList(nodeTrigger, nodeAgent, nodeSwitch, nodeEmpty, nodeError, nodeDeepSeek);
        List<FlowEdge<?>> edges = Lists.newArrayList(edgeTrigger, edgeSwitch1, edgeSwitch2, edgeDeepSeek);

        // agent group flow node
        var nodeAgentGroup = new AgentGroupFlowNode(UUID.randomUUID().toString(), "AgentGroup", "An agent group that write code to verify the calculation in query.", 3);
        var edgeAgent = new ConnectionEdge(UUID.randomUUID().toString()).connect(nodeAgent.getId(), nodeAgentGroup.getId());
        var edgeAgentGroup = new ConnectionEdge(UUID.randomUUID().toString()).connect(nodeAgentGroup.getId(), nodeSwitch.getId());
        // agent group llm settings
        var edgeDeepSeek2 = new SettingEdge(UUID.randomUUID().toString()).connect(nodeAgentGroup.getId(), nodeDeepSeek.getId());
        // agent group handoff settings
        var nodeHandoff = new HybridHandoffFlowNode(UUID.randomUUID().toString(), "HybridHandoff");
        var edgeHandoff = new SettingEdge(UUID.randomUUID().toString()).connect(nodeAgentGroup.getId(), nodeHandoff.getId());
        // agent group handoff's moderator agent settings
        var nodeAgentModerator = BuiltinModeratorAgentFlowNode.of();
        var edgeAgentModerator = new SettingEdge(UUID.randomUUID().toString()).connect(nodeHandoff.getId(), nodeAgentModerator.getId());
        var edgeAgentModeratorLLM = new SettingEdge(UUID.randomUUID().toString()).connect(nodeAgentModerator.getId(), nodeDeepSeek.getId());
        // agent group agents settings - requirement extractor agent
        var nodeRequirement = new AgentFlowNode(UUID.randomUUID().toString(), "Original Calc Requirement Extractor");
        nodeRequirement.setDescription("Extract the original calculation request from the query.Only output the query, do not add the calculation result.");
        nodeRequirement.setSystemPrompt("""
                Analysis the query and extract the original calculate request.
                Only output the query, do not add the calculation result.
                """);
        var edgeRequirement = new SettingEdge(UUID.randomUUID().toString()).connect(nodeAgentGroup.getId(), nodeRequirement.getId());
        var edgeRequirementLLM = new SettingEdge(UUID.randomUUID().toString()).connect(nodeRequirement.getId(), nodeDeepSeek.getId());
        // agent group agents settings - python agent
        var nodePythonExecutor = BuiltinPythonAgentFlowNode.of("""
                Python code only print the calculate result number, do not add any other information.
                Last line of the code should be the `print(y - x)` statement.
                Do not use sympy or any other library to do the calculation.
                """);
        var edgePythonExecutor = new SettingEdge(UUID.randomUUID().toString()).connect(nodeAgentGroup.getId(), nodePythonExecutor.getId());
        var edgePythonLLM = new SettingEdge(UUID.randomUUID().toString()).connect(nodePythonExecutor.getId(), nodeDeepSeek.getId());
        // python agent tool settings
        var nodePythonTool = BuiltinPythonToolFlowNode.of();
        var edgePythonTool = new SettingEdge(UUID.randomUUID().toString()).connect(nodePythonExecutor.getId(), nodePythonTool.getId());

        List<FlowNode<?>> groupNodes = Lists.newArrayList(nodeAgentGroup, nodeHandoff, nodeAgentModerator, nodeRequirement, nodePythonExecutor, nodePythonTool);
        List<FlowEdge<?>> groupEdges = Lists.newArrayList(edgeAgent, edgeAgentGroup, edgeDeepSeek2, edgeHandoff, edgeAgentModerator, edgeAgentModeratorLLM, edgeRequirement, edgeRequirementLLM, edgePythonExecutor, edgePythonLLM, edgePythonTool);

        nodes.addAll(groupNodes);
        edges.addAll(groupEdges);
        return executeFlow(nodeTrigger, nodes, edges, query);
    }

    private String executeFlow(FlowNode<?> nodeTrigger, List<FlowNode<?>> nodes, List<FlowEdge<?>> edges, String query) {
        var flow = Flow.builder().id("test_id").name("test_flow").description("test flow")
                .nodes(nodes)
                .edges(edges)
                .flowOutputUpdatedEventListener((node, q, rst) -> logger.info("Workflow[{}], input: {}, result: {}", node.getName(), q, rst.text()))
                .build();
        return flow.run(nodeTrigger.getId(), query, ExecutionContext.empty());
    }

    public OrderIssueResponse groupStart(String query) {
        var group = OrderIssueGroup.create(llmProviders.getProvider(), persistenceProvider, userInfoService);
        group.run(query, ExecutionContext.empty());
        var rsp = new OrderIssueResponse();
        rsp.content = group.getOutput();
        rsp.conversation = List.of(group.getConversation().split("\n"));
        if (group.getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT) {
            rsp.id = group.save(UUID.randomUUID().toString());
            return rsp;
        }
        return rsp;
    }

    public OrderIssueResponse groupFinish(String id, String query) {
        var group = OrderIssueGroup.create(llmProviders.getProvider(), persistenceProvider, userInfoService);
        group.load(id);
        group.run(query, ExecutionContext.empty());
        var rsp = new OrderIssueResponse();
        rsp.content = group.getOutput();
        rsp.conversation = List.of(group.getConversation().split("\n"));
        return rsp;
    }

    public String chat(String query) {
        var agent = Agent.builder()
                .name("test-agent")
                .description("test agent")
                .systemPrompt("you are a helpful AI assistant, you can answer any question.")
                .streaming(true)
                .streamingCallback(new LoggingStreamingCallback())
                .toolCalls(Functions.from(weatherService, "get", "getAirQuality"))
                .llmProvider(llmProviders.getProvider(LLMProviderType.AZURE_INFERENCE)).build();
        return agent.run(query, ExecutionContext.empty());
    }

    public String userInputStart() {
        var agent = UserInputAgent.builder().persistenceProvider(persistenceProvider).build();
        agent.run("test need user input", ExecutionContext.empty());
        return agent.save(UUID.randomUUID().toString());
    }

    public String userInputFinish(String id, String query) {
        var agent = UserInputAgent.builder().persistenceProvider(persistenceProvider).build();
        agent.load(id);
        return agent.run(query, ExecutionContext.empty());
    }

    public String mcpToolCallTest(MCPToolCallRequest request) {
        var mcpClientService = new McpClientService(new McpClientServerConfig(request.url, "sse", "git", "git operator"));
        var agent = Agent.builder()
                .name("mcp-tool-call-agent")
                .description("mcp tool call agent")
                .systemPrompt("you are a tool call agent")
                .promptTemplate("")
                .toolCalls(McpToolCalls.from(mcpClientService))
                .llmProvider(llmProviders.getProvider()).build();
        return agent.run(request.query, ExecutionContext.empty());
    }


    public String optimize(String prompt) {
        var agent = PromptOptimizeAgent.of(llmProviders.getProvider());
        return agent.run(prompt, ExecutionContext.empty());
    }

    public String summaryOptimize(String prompt) {
        var agent = PromptOptimizeAgent.of(llmProviders.getProvider());
        agent.run(prompt, ExecutionContext.empty());
        var summaryAgent = DefaultSummaryAgent.of(llmProviders.getProvider());
        var summaryChain = AgentGroup.builder()
                .name("summary-chain")
                .description("summary the chain")
                .agents(List.of(agent, summaryAgent))
                .handoffType(HandoffType.DIRECT)
                .maxRound(1)
                .llmProvider(llmProviders.getProvider()).build();
        return summaryChain.run(prompt, ExecutionContext.empty());
    }

    public String debate(String topic) {
        var proAgent = Agent.builder()
                .name("debate-con-agent")
                .description("con debater")
                .systemPrompt("You are the negative side in a debate, and you do not support the viewpoint of the topic.You will analyze the arguments from the affirmative side and refute them one by one.")
                .promptTemplate("pro viewpoints: ")
                .llmProvider(llmProviders.getProvider()).build();
        var conAgent = Agent.builder()
                .name("debate-pro-agent")
                .description("pro debater")
                .systemPrompt("You are the affirmative side in a debate, and you support the viewpoint of the topic.you will elaborate and provide examples from multiple perspectives to illustrate why you support this viewpoint.")
                .promptTemplate("topic: ")
                .llmProvider(llmProviders.getProvider()).build();
        conAgent.addStatusChangedEventListener(NodeStatus.RUNNING, new DefaultAgentRunningEventListener());
        var debateChain = AgentGroup.builder()
                .name("debate-chain")
                .description("chain of debate")
                .agents(List.of(proAgent, conAgent))
                .handoffType(HandoffType.DIRECT)
                .maxRound(3)
                .llmProvider(llmProviders.getProvider()).build();
        return debateChain.run(topic, ExecutionContext.empty());
    }

    public String function(String prompt) {
        var agent = Agent.builder()
                .name("weather-agent")
                .description("get weather of a city")
                .systemPrompt("Your are an assistant help user get weather for example temperature or air quality of gaven cities. "
                              + "If query do not contain a city in the gave list, return 'I am weather toolkit, I don't known other things, so which city's weather you want to check?'.")
                .promptTemplate("topic: ")
                .toolCalls(Functions.from(weatherService, "get", "getAirQuality"))
                .llmProvider(llmProviders.getProvider()).build();
        return agent.run(prompt, ExecutionContext.empty());
    }

    private final class LoggingStreamingCallback implements StreamingCallback {
        @Override
        public void onChunk(String chunk) {
            logger.info(chunk);
        }

        @Override
        public void onComplete() {
            logger.info("streaming complete");
        }

        @Override
        public void onError(Throwable error) {
            logger.error("streaming error", error);
        }
    }
}
