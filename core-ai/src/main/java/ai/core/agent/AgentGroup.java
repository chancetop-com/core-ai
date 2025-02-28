package ai.core.agent;

import ai.core.agent.planning.DefaultPlanning;
import ai.core.defaultagents.ModeratorAgent;
import ai.core.llm.LLMProvider;
import ai.core.termination.Termination;
import ai.core.termination.terminations.MaxRoundTermination;
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
            planning.planning(moderator, query);
            if (Strings.isBlank(planning.nextAgentName())) {
                if (Termination.DEFAULT_TERMINATION_WORD.equals(planning.nextAction())) {
                    updateNodeStatus(NodeStatus.COMPLETED);
                    return getOutput();
                }
                throw new IllegalArgumentException("moderator return agent name is null");
            }
            var next = getAgentByName(planning.nextAgentName());
            if (next == null) {
                throw new IllegalArgumentException("moderator return agent name not found");
            }
            current = next.getName();
            setInput(planning.nextQuery());
            var output = next.run(planning.nextQuery(), variables);
            setOutput(output);
            addMessages(next.getMessages());
            setRound(getRound() + 1);
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
            logger.info("round: {}/{}, agent: {}, input: {}, output: {}", getRound(), getMaxRound(), next.getName(), getInput(), getOutput());
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

    public static class Builder extends Node.Builder<Builder, AgentGroup> {
        private List<Node<?>> agents;
        private LLMProvider llmProvider;
        private Planning planning;

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
            agent.planning = this.planning;
            if (agent.moderator == null && this.llmProvider != null) {
                agent.moderator = ModeratorAgent.of(agent, this.llmProvider, this.description);
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
