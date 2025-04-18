package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.agent.AgentGroup;
import ai.core.agent.handoff.HandoffType;
import ai.core.agent.NodeStatus;
import ai.core.agent.UserInputAgent;
import ai.core.agent.listener.listeners.DefaultAgentRunningEventListener;
import ai.core.defaultagents.DefaultSummaryAgent;
import ai.core.example.api.example.MCPToolCallRequest;
import ai.core.example.api.example.OrderIssueResponse;
import ai.core.flow.Flow;
import ai.core.flow.FlowPersistence;
import ai.core.flow.edges.ConnectionEdge;
import ai.core.flow.edges.SettingEdge;
import ai.core.flow.nodes.AgentFlowNode;
import ai.core.flow.nodes.DeepSeekFlowNode;
import ai.core.flow.nodes.EmptyFlowNode;
import ai.core.flow.nodes.OperatorSwitchFlowNode;
import ai.core.flow.nodes.ThrowErrorFlowNode;
import ai.core.flow.nodes.WebhookTriggerFlowNode;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.AzureInferenceProvider;
import ai.core.mcp.client.MCPClientService;
import ai.core.mcp.client.MCPServerConfig;
import ai.core.persistence.providers.TemporaryPersistenceProvider;
import ai.core.tool.function.Functions;
import ai.core.tool.mcp.MCPToolCalls;
import core.framework.inject.Inject;

import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class ExampleService {
    @Inject
    TemporaryPersistenceProvider persistenceProvider;
    @Inject
    AzureInferenceProvider llmProvider;
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
        var edgeTrigger = new ConnectionEdge(UUID.randomUUID().toString());
        var edgeAgent = new ConnectionEdge(UUID.randomUUID().toString());
        var edgeSwitch1 = new ConnectionEdge(UUID.randomUUID().toString(), "1");
        var edgeSwitch2 = new ConnectionEdge(UUID.randomUUID().toString(), "2");
        edgeTrigger.connect(nodeTrigger.getId(), nodeAgent.getId());
        edgeAgent.connect(nodeAgent.getId(), nodeSwitch.getId());
        edgeSwitch1.connect(nodeSwitch.getId(), nodeError.getId());
        edgeSwitch2.connect(nodeSwitch.getId(), nodeEmpty.getId());
        var nodeDeepSeek = new DeepSeekFlowNode(UUID.randomUUID().toString(), "DeepSeek", llmProviders);
        var edgeDeepSeek = new SettingEdge(UUID.randomUUID().toString());
        edgeDeepSeek.connect(nodeAgent.getId(), nodeDeepSeek.getId());
        nodeAgent.setSystemPrompt("""
                Your are a calculator, you can do any math calculation, only return the result.
                """);
        var flow = Flow.builder()
                .id("test_id")
                .name("test_flow")
                .description("test flow")
                .persistence(new FlowPersistence())
                .persistenceProvider(new TemporaryPersistenceProvider())
                .nodes(List.of(nodeTrigger, nodeAgent, nodeSwitch, nodeEmpty, nodeError, nodeDeepSeek))
                .edges(List.of(edgeTrigger, edgeAgent, edgeSwitch1, edgeSwitch2, edgeDeepSeek))
                .build();
        return flow.execute(nodeTrigger.getId(), query, null);
    }

    public OrderIssueResponse groupStart(String query) {
        var group = OrderIssueGroup.create(llmProvider, persistenceProvider, userInfoService);
        group.run(query, null);
        var rsp = new OrderIssueResponse();
        rsp.content = group.getOutput();
        rsp.conversation = List.of(group.getConversation().split("\n"));
        if (group.getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT) {
            rsp.id = group.save();
            return rsp;
        }
        return rsp;
    }

    public OrderIssueResponse groupFinish(String id, String query) {
        var group = OrderIssueGroup.create(llmProvider, persistenceProvider, userInfoService);
        group.load(id);
        group.run(query, null);
        var rsp = new OrderIssueResponse();
        rsp.content = group.getOutput();
        rsp.conversation = List.of(group.getConversation().split("\n"));
        return rsp;
    }

    public String chat(String query) {
        var agent = ThinkingClaudeAgent.of(llmProvider);
        return agent.run(query, null);
    }

    public String userInputStart() {
        var agent = UserInputAgent.builder().persistenceProvider(persistenceProvider).build();
        agent.run("test need user input", null);
        return agent.save();
    }

    public String userInputFinish(String id, String query) {
        var agent = UserInputAgent.builder().persistenceProvider(persistenceProvider).build();
        agent.load(id);
        return agent.run(query, null);
    }

    public String mcpToolCallTest(MCPToolCallRequest request) {
        var mcpClientService = new MCPClientService(new MCPServerConfig(request.url, "git", "git operator"));
        var agent = Agent.builder()
                .name("mcp-tool-call-agent")
                .description("mcp tool call agent")
                .systemPrompt("you are a tool call agent")
                .promptTemplate("")
                .toolCalls(MCPToolCalls.from(mcpClientService))
                .llmProvider(llmProvider).build();
        return agent.run(request.query, null);
    }


    public String optimize(String prompt) {
        var agent = PromptOptimizeAgent.of(llmProvider);
        return agent.run(prompt, null);
    }

    public String summaryOptimize(String prompt) {
        var agent = PromptOptimizeAgent.of(llmProvider);
        agent.run(prompt, null);
        var summaryAgent = DefaultSummaryAgent.of(llmProvider);
        var summaryChain = AgentGroup.builder()
                .name("summary-chain")
                .description("summary the chain")
                .agents(List.of(agent, summaryAgent))
                .handoffType(HandoffType.DIRECT)
                .maxRound(1)
                .llmProvider(llmProvider).build();
        return summaryChain.run(prompt, null);
    }

    public String debate(String topic) {
        var proAgent = Agent.builder()
                .name("debate-con-agent")
                .description("con debater")
                .systemPrompt("You are the negative side in a debate, and you do not support the viewpoint of the topic.You will analyze the arguments from the affirmative side and refute them one by one.")
                .promptTemplate("pro viewpoints: ")
                .llmProvider(llmProvider).build();
        var conAgent = Agent.builder()
                .name("debate-pro-agent")
                .description("pro debater")
                .systemPrompt("You are the affirmative side in a debate, and you support the viewpoint of the topic.you will elaborate and provide examples from multiple perspectives to illustrate why you support this viewpoint.")
                .promptTemplate("topic: ")
                .llmProvider(llmProvider).build();
        conAgent.addStatusChangedEventListener(NodeStatus.RUNNING, new DefaultAgentRunningEventListener());
        var debateChain = AgentGroup.builder()
                .name("debate-chain")
                .description("chain of debate")
                .agents(List.of(proAgent, conAgent))
                .handoffType(HandoffType.DIRECT)
                .maxRound(3)
                .llmProvider(llmProvider).build();
        return debateChain.run(topic, null);
    }

    public String function(String prompt) {
        var agent = Agent.builder()
                .name("weather-agent")
                .description("get weather of a city")
                .systemPrompt("Your are an assistant help user get weather for example temperature or air quality of gaven cities. "
                              + "If query do not contain a city in the gave list, return 'I am weather toolkit, I don't known other things, so which city's weather you want to check?'.")
                .promptTemplate("topic: ")
                .toolCalls(Functions.from(weatherService, "get", "getAirQuality"))
                .llmProvider(llmProvider).build();
        return agent.run(prompt, null);
    }
}
