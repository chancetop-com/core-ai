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
    private String current;

    @Override
    String execute(String query, Map<String, Object> variables) {
        try {
            return executeWithException(query, variables);
        } catch (Exception e) {
            if (current == null) {
                throw new RuntimeException(Strings.format("Failed at moderator: {}", e.getMessage()), e);
            }
            var currentAgent = getAgentByName(current);
            throw new RuntimeException(Strings.format("Failed at {}<{}>: {}", current, currentAgent.getId(), e.getMessage()), e);
        }
    }

    String executeWithException(String rawQuery, Map<String, Object> variables) {
        var query = rawQuery;
        setInput(query);
        updateNodeStatus(NodeStatus.RUNNING);

        setRound(0);
        while (!terminateCheck()) {
            var text = planning.planning(moderator, query, variables);
            setRawOutput(text);
            addResponseChoiceMessages(List.of(
                    Message.of(AgentRole.USER, moderator.getName(), query),
                    Message.of(AgentRole.ASSISTANT, planning.planningText(), moderator.getName(), null, null, null)));
            if (Strings.isBlank(planning.nextAgentName())) {
                if (Termination.DEFAULT_TERMINATION_WORD.equals(planning.nextAction())) {
                    updateNodeStatus(NodeStatus.COMPLETED);
                    return getOutput();
                }
                query = "The next agent name is empty, please check the planning result";
                continue;
            }
            var next = getAgentByName(planning.nextAgentName());
            if (next == null) {
                query = "The next agent is not found, please check the planning result";
                continue;
            }
            current = next.getName();
            setInput(planning.nextQuery());
            var output = next.run(planning.nextQuery(), variables);
            setRawOutput(output);
            setOutput(output);
            addResponseChoiceMessages(next.getMessages().subList(1, next.getMessages().size()));
            setRound(getRound() + 1);
            logger.info("round: {}/{}, agent: {}, input: {}, output: {}", getRound(), getMaxRound(), next.getName(), getInput(), getOutput());
            if (next.getType() == NodeType.USER_INPUT && next.getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT) {
                updateNodeStatus(NodeStatus.WAITING_FOR_USER_INPUT);
                return output;
            }
            if (Termination.DEFAULT_TERMINATION_WORD.equals(planning.nextAction())) {
                updateNodeStatus(NodeStatus.COMPLETED);
                return output;
            }
            next.clearMessages();
            query = output;
        }

        return getOutput();
    }

    public Node<?> getAgentByName(String name) {
        return agents.stream().filter(node -> node.getName().equals(name)).findFirst().orElseThrow(() -> new RuntimeException("agent not found: " + name));
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
        return AgentsInfo.agentsInfo(agents);
    }

    public static class AgentsInfo {
        public static String agentsInfo(List<Node<?>> agents) {
            return JSON.toJSON(AgentsInfo.of(agents.stream().map(agent -> {
                var agentInfo = AgentInfo.of(agent.getName(), agent.getDescription());
                if (agent instanceof Agent) {
                    agentInfo.functions = ((Agent) agent).getToolCalls().stream().map(ToolCall::toString).toList();
                }
                return agentInfo;
            }).toList()));
        }

        public static AgentsInfo of(List<AgentInfo> agents) {
            var dto = new AgentsInfo();
            dto.agents = agents;
            return dto;
        }

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
            agent.agents = this.agents;
            agent.moderator = this.moderator;
            if (agent.moderator == null) {
                agent.moderator = DefaultModeratorAgent.of(agent, this.llmProvider);
            }
            agent.planning = this.planning;
            if (agent.planning == null) {
                agent.planning = new DefaultPlanning();
            }
            if (agent.getMaxRound() <= 0) {
                agent.setMaxRound(5);
            }
            agent.llmProvider = this.llmProvider;
            agent.addTermination(new MaxRoundTermination());
            return agent;
        }
    }
}
