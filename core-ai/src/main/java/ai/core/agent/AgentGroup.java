package ai.core.agent;

import ai.core.agent.handoff.HandoffType;
import ai.core.agent.handoff.handoffs.AutoHandoff;
import ai.core.agent.handoff.Handoff;
import ai.core.agent.handoff.handoffs.DirectHandoff;
import ai.core.agent.handoff.handoffs.HybridAutoDirectHandoff;
import ai.core.agent.planning.Planning;
import ai.core.agent.planning.plannings.DefaultPlanning;
import ai.core.defaultagents.DefaultModeratorAgent;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.RoleType;
import ai.core.prompt.SystemVariables;
import ai.core.termination.Termination;
import ai.core.termination.terminations.MaxRoundTermination;
import ai.core.tool.ToolCall;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class AgentGroup extends Node<AgentGroup> {
    public static Builder builder() {
        return new Builder();
    }
    private final Logger logger = LoggerFactory.getLogger(AgentGroup.class);

    LLMProvider llmProvider;
    List<Node<?>> agents;
    Planning planning;
    Handoff handoff;
    private Node<?> currentAgent;
    private String currentQuery;

    @Override
    String execute(String query, Map<String, Object> variables) {
        try {
            return executeWithException(query, variables);
        } catch (Exception e) {
            throw new RuntimeException(Strings.format("Failed at {}<{}>: {}", this.currentAgent, currentAgent.getId(), e.getMessage()), e);
        }
    }

    @Override
    void setChildrenParentNode() {
        agents.forEach(v -> v.setParentNode(this));
        if (handoff instanceof AutoHandoff) {
            ((AutoHandoff) handoff).moderator().setParentNode(this);
        }
    }

    String executeWithException(String rawQuery, Map<String, Object> variables) {
        setupAgentGroupSystemVariables();
        currentQuery = rawQuery;
        startRunning();
        while (notTerminated()) {
            if (currentTokenUsageOutOfMax(currentQuery, llmProvider.maxTokens())) {
                currentQuery = handleToShortQuery(currentQuery, getPreviousQuery());
            }

            try {
                handoff.handoff(this, planning, variables);
            } catch (Exception e) {
                currentQuery = Strings.format("JSON resolve failed, please check the planning result: ", e.getMessage());
                setRound(getRound() + 1);
                continue;
            }

            // planning think the previous round is completed
            if (finished(false)) return getOutput() == null ? getInput() : getOutput();
            if (planningFailed()) continue;

            currentAgent = getAgentByName(planning.nextAgentName());
            setInput(planning.nextQuery());
            String output;

            try {
                output = currentAgent.run(planning.nextQuery(), variables);
            } catch (Exception e) {
                logger.warn("round: {}/{} failed\n agent: {}\nerror: {}\n planning: {}\n input: {}", getRound(), getMaxRound(), currentAgent.getName(), e.getMessage(), planning.nextQuery(), currentAgent.getInput());
                currentQuery = Strings.format("Failed to run agent<{}>: {}\n input: {}", currentAgent.getName(), e.getMessage(), currentAgent.getInput());
                setRound(getRound() + 1);
                continue;
            }

            afterRunAgent(output);
            currentQuery = output;

            logger.info("group round: {}/{}, agent: {}, input: {}, output: {}", getRound(), getMaxRound(), currentAgent.getName(), getInput(), getOutput());

            if (waitingForUserInput()) return output;
            // planning think this agent can finish the task
            if (finished(true)) return output;

            roundCompleted();
            setRound(getRound() + 1);
        }

        return Strings.format("Run out of round: {}/{}, Last round output: {}", getRound(), getMaxRound(), getOutput());
    }

    private void setupAgentGroupSystemVariables() {
        var systemVariables = getSystemVariables();
        systemVariables.put(SystemVariables.AGENT_GROUP_CURRENT_ROUND, getRound());
        systemVariables.put(SystemVariables.AGENT_GROUP_CURRENT_INPUT, getInput());
    }

    private String getPreviousQuery() {
        return currentAgent == null ? currentQuery : getMessages().getLast().content;
    }

    private void roundCompleted() {
        currentAgent.clearShortTermMemory();
    }

    private boolean waitingForUserInput() {
        if (currentAgent.getType() == NodeType.USER_INPUT && currentAgent.getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT) {
            updateNodeStatus(NodeStatus.WAITING_FOR_USER_INPUT);
            return true;
        }
        return false;
    }

    private boolean finished(boolean isCurrentRoundFinished) {
        if (Termination.DEFAULT_TERMINATION_WORD.equals(planning.nextAction())
                && isCurrentRoundFinished
                || Strings.isBlank(planning.nextAgentName())) {
            updateNodeStatus(NodeStatus.COMPLETED);
            return true;
        }
        return false;
    }

    private boolean planningFailed() {
        if (Strings.isBlank(planning.nextAgentName())) {
            currentQuery = "The next agent name is empty, please check the planning result";
            return true;
        }
        var next = getAgentByName(planning.nextAgentName());
        if (next == null) {
            currentQuery = "The next agent is not found, please check the planning result";
            return true;
        }
        return false;
    }

    private void afterRunAgent(String output) {
        setRawOutput(currentAgent.getRawOutput());
        setOutput(output);
    }

    private void startRunning() {
        setInput(currentQuery);
        setRound(1);
        updateNodeStatus(NodeStatus.RUNNING);
    }

    @Override
    public void clearShortTermMemory() {
        if (handoff instanceof AutoHandoff) {
            ((AutoHandoff) handoff).moderator().clearShortTermMemory();
        }
        currentAgent = null;
        super.clearShortTermMemory();
    }

    public Node<?> getCurrentAgent() {
        return currentAgent;
    }

    public void setCurrentAgent(Node<?> currentAgent) {
        this.currentAgent = currentAgent;
    }

    public Handoff getHandoff() {
        return handoff;
    }

    public Planning getPlanning() {
        return planning;
    }

    public List<Node<?>> getAgents() {
        return agents;
    }

    public String getCurrentQuery() {
        return currentQuery;
    }

    public Node<?> getAgentByName(String name) {
        return agents.stream().filter(node -> node.getName().equals(name)).findFirst().orElse(null);
    }

    public String getConversation() {
        return getMessages().stream().filter(v -> v.role != RoleType.SYSTEM).map(v -> {
            if (v.role == RoleType.USER) {
                return Strings.format("{}<request>: {}", v.getName(), v.content);
            }
            if (v.toolCalls == null || v.toolCalls.isEmpty()) {
                return Strings.format("{}<response>: {}", v.getName(), v.content);
            } else {
                return Strings.format("{}<response>: {}; {function: {}({})}", v.getName(), v.content, v.toolCalls.getFirst().function.name, v.toolCalls.getFirst().function.arguments);
            }
        }).collect(Collectors.joining("\n"));
    }

    @Override
    public String toString() {
        return AgentsInfo.agentsInfo(getName(), getDescription(), agents);
    }

    public static class AgentsInfo {
        public static String agentsInfo(List<Node<?>> agents) {
            return agentsInfo("", "", agents);
        }

        public static String agentsInfo(String name, String description, List<Node<?>> agents) {
            return JSON.toJSON(of(name, description, agents.stream().map(agent -> {
                var agentInfo = AgentInfo.of(agent.getName(), agent.getDescription());
                if (agent instanceof Agent) {
                    agentInfo.functions = ((Agent) agent).getToolCalls().stream().map(ToolCall::toString).toList();
                }
                return agentInfo;
            }).toList()));
        }

        public static AgentsInfo of(String name, String description, List<AgentInfo> agents) {
            var dto = new AgentsInfo();
            dto.name = name;
            dto.description = description;
            dto.agents = agents;
            return dto;
        }

        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "agents")
        public List<AgentInfo> agents;
    }

    public static class AgentInfo {
        public static AgentInfo of(String name, String description) {
            var dto = new AgentInfo();
            dto.name = name;
            dto.description = description;
            return dto;
        }

        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "functions")
        public List<String> functions;
    }

    public static class Builder extends NodeBuilder<Builder, AgentGroup> {
        private List<Node<?>> agents;
        private LLMProvider llmProvider;
        private Planning planning;
        private HandoffType handoffType;
        private Handoff handoff;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder agents(List<Node<?>> agents) {
            this.agents = agents;
            return this;
        }

        public Builder llmProvider(LLMProvider llmProvider) {
            this.llmProvider = llmProvider;
            return this;
        }

        public Builder planning(Planning planning) {
            this.planning = planning;
            return this;
        }

        public Builder handoffType(HandoffType handoffType) {
            this.handoffType = handoffType;
            return this;
        }

        public Builder handoff(Handoff handoff) {
            this.handoff = handoff;
            return this;
        }

        public AgentGroup build() {
            if (this.agents == null || this.agents.isEmpty()) {
                throw new IllegalArgumentException("agents is required");
            }
            if (this.llmProvider == null) {
                throw new IllegalArgumentException("llmProvider is required");
            }
            if (name == null) {
                name = "assistant-agent-group";
            }
            if (description == null) {
                description = "assistant agent group that help with user";
            }
            var agent = new AgentGroup();
            this.nodeType = NodeType.GROUP;
            persistence(new AgentGroupPersistence());
            build(agent);
            var haveUserInputAgent = agents.stream().anyMatch(v -> v.getType() == NodeType.USER_INPUT);
            if (haveUserInputAgent && persistenceProvider == null) {
                throw new IllegalArgumentException("persistenceProvider is required when have user input agent");
            }
            agent.addTermination(new MaxRoundTermination());
            agent.llmProvider = this.llmProvider;
            agent.agents = this.agents;
            agent.handoff = this.handoff;
            agent.planning = this.planning;
            if (agent.planning == null) {
                agent.planning = new DefaultPlanning();
            }
            if (agent.getMaxRound() <= 0) {
                agent.setMaxRound(5);
            }
            if (handoff == null) {
                buildHandoff(agent);
            }
            buildHandoff(agent);
            agent.setChildrenParentNode();

            var systemVariables = agent.getSystemVariables();
            systemVariables.put(SystemVariables.AGENT_GROUP_AGENTS, AgentsInfo.agentsInfo(agents));
            systemVariables.put(SystemVariables.AGENT_GROUP_NAME, agent.getName());
            systemVariables.put(SystemVariables.AGENT_GROUP_DESCRIPTION, agent.getDescription());
            systemVariables.put(SystemVariables.AGENT_GROUP_MAX_ROUND, agent.getMaxRound());
            return agent;
        }

        private void buildHandoff(AgentGroup agent) {
            if (handoff != null) return;
            if (handoffType == HandoffType.MANUAL && agent.handoff == null) {
                throw new IllegalArgumentException("handoff is required when handoffType is MANUAL");
            }
            if (handoffType == null) {
                handoffType = HandoffType.HYBRID;
            }
            if (handoffType == HandoffType.DIRECT) {
                agent.handoff = new DirectHandoff();
            }
            if (handoffType == HandoffType.HYBRID) {
                agent.handoff = new HybridAutoDirectHandoff(buildDefaultModeratorAgent());
            }
            if (handoffType == HandoffType.AUTO) {
                agent.handoff = new AutoHandoff(buildDefaultModeratorAgent());
            }
        }

        private Agent buildDefaultModeratorAgent() {
            return DefaultModeratorAgent.of(this.llmProvider, this.llmProvider.config.getModel());
        }
    }
}
