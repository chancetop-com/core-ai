package ai.core.agent;

import ai.core.agent.planning.DefaultPlanning;
import ai.core.defaultagents.DefaultModeratorAgent;
import ai.core.llm.LLMProvider;
import ai.core.llm.providers.inner.Message;
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
    Agent moderator;
    Planning planning;
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
        moderator.setParentNode(this);
    }

    String executeWithException(String rawQuery, Map<String, Object> variables) {
        currentQuery = rawQuery;
        startRunning();
        while (!terminateCheck()) {
            setRound(getRound() + 1);
            if (currentTokenUsageOutOfMax(currentQuery, llmProvider.maxTokens())) {
                currentQuery = handleToShortQuery(currentQuery, getPreviousQuery());
            }

            try {
                currentAgent = moderator;
                var text = planning.planning(moderator, currentQuery, variables);
                planningFinished(currentQuery, text, currentAgent == null ? "user" : currentAgent.getName());
            } catch (Exception e) {
                currentQuery = Strings.format("JSON resolve failed, please check the planning result: ", e.getMessage());
                continue;
            }

            // planning think the previous round is completed
            if (finished()) return getOutput();
            if (planningFailed()) continue;

            currentAgent = getAgentByName(planning.nextAgentName());
            setInput(planning.nextQuery());
            String output;

            try {
                output = currentAgent.run(planning.nextQuery(), variables);
            } catch (Exception e) {
                logger.warn("round: {}/{} failed, agent: {}, planning: {}, input: {}", getRound(), getMaxRound(), currentAgent.getName(), planning.nextQuery(), currentAgent.getInput());
                currentQuery = Strings.format("Failed to run agent<{}>: {}", currentAgent.getName(), e.getMessage());
                continue;
            }

            afterRunAgent(output);
            currentQuery = output;

            logger.info("round: {}/{}, agent: {}, input: {}, output: {}", getRound(), getMaxRound(), currentAgent.getName(), getInput(), getOutput());

            if (waitingForUserInput()) return output;
            // planning think this agent can finish the task
            if (finished()) return output;

            roundCompleted();
        }

        return Strings.format("Run out of round: {}/{}, Last round output: {}", getRound(), getMaxRound(), getOutput());
    }

    private String getPreviousQuery() {
        return currentAgent == null ? null : moderator.getMessages().getLast().content;
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

    private boolean finished() {
        if (Termination.DEFAULT_TERMINATION_WORD.equals(planning.nextAction())) {
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
        addResponseChoiceMessages(currentAgent.getMessages().subList(1, currentAgent.getMessages().size()));
    }

    private void planningFinished(String query, String text, String queryFrom) {
        setRawOutput(moderator.getRawOutput());
        addResponseChoiceMessages(List.of(
                Message.of(AgentRole.USER, queryFrom, query),
                Message.of(AgentRole.ASSISTANT, text, moderator.getName(), null, null, null)));
    }

    private void startRunning() {
        setInput(currentQuery);
        updateNodeStatus(NodeStatus.RUNNING);
    }

    @Override
    public void clearShortTermMemory() {
        moderator.clearShortTermMemory();
        currentAgent = null;
        super.clearShortTermMemory();
    }

    public Agent getModerator() {
        return moderator;
    }

    public Planning getPlanning() {
        return planning;
    }

    public Node<?> getAgentByName(String name) {
        return agents.stream().filter(node -> node.getName().equals(name)).findFirst().orElse(null);
    }

    public List<Node<?>> getAgents() {
        return agents;
    }

    public String getConversation() {
        return getMessages().stream().filter(v -> v.role != AgentRole.SYSTEM).map(v -> {
            if (v.role == AgentRole.USER) {
                return Strings.format("{}<request>: {}", v.name, v.content);
            }
            if (v.toolCalls == null || v.toolCalls.isEmpty()) {
                return Strings.format("{}<response>: {}", v.name, v.content);
            } else {
                return Strings.format("{}<response>: {}; {function: {}({})}", v.name, v.content, v.toolCalls.getFirst().function.name, v.toolCalls.getFirst().function.arguments);
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
            return JSON.toJSON(AgentsInfo.of(name, description, agents.stream().map(agent -> {
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

    public static class Builder extends Node.Builder<Builder, AgentGroup> {
        private List<Node<?>> agents;
        private LLMProvider llmProvider;
        private Planning planning;
        private Agent moderator;

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

        public Builder moderator(Agent moderator) {
            this.moderator = moderator;
            return this;
        }

        public AgentGroup build() {
            if (this.agents == null || this.agents.isEmpty()) {
                throw new IllegalArgumentException("agents is required");
            }
            if (this.llmProvider == null) {
                throw new IllegalArgumentException("llmProvider is required");
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
            agent.moderator = this.moderator;
            if (agent.moderator == null) {
                agent.moderator = DefaultModeratorAgent.of(this.llmProvider, this.llmProvider.config.getModel(), this.description, this.agents, null);
            }
            agent.planning = this.planning;
            if (agent.planning == null) {
                agent.planning = new DefaultPlanning();
            }
            if (agent.getMaxRound() <= 0) {
                agent.setMaxRound(5);
            }
            agent.setChildrenParentNode();
            return agent;
        }
    }
}
