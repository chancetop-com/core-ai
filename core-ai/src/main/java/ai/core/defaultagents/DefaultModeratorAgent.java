package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.agent.AgentGroup;
import ai.core.agent.Node;
import ai.core.agent.formatter.formatters.DefaultJsonFormatter;
import ai.core.llm.LLMProvider;
import core.framework.util.Strings;

import java.util.List;

/**
 * @author stephen
 */
public class DefaultModeratorAgent {
    /**
     * Create a moderator agent for a role play game to solve task by guide the conversation and choose the next speaker agent.
     *
     * @param llmProvider the LLM provider
     * @param model the model
     * @param goal the goal, the task to solve
     * @param agents the agents list
     * @param contextVariableTemplate promptTemplate that provider the context variables if needed, null for no context provided to moderator
     * @return the moderator agent
     */
    public static Agent of(LLMProvider llmProvider, String model, String goal, List<Node<?>> agents, String contextVariableTemplate) {
        return Agent.builder()
                .name("moderator-agent")
                .description("moderator of a role play game to solve task by guide the conversation and choose the next speaker agent")
                .systemPrompt(Strings.format("""
                        You are in a role play game to solve task by guide the conversation and choose the next speaker agent, the goal is:
                        {}.
                        The following agents list are available:
                        {}.
                        You need carefully review the capabilities of each agent, including the inputs and outputs of their tolls and functions to planning the conversation and choose the next agent to play.
                        Read the conversation, then select the next agent from the agents list to play.
                        Please generate the detailed query for the next agent if needed, including all necessary context that next agent might need.
                        You only do the planning and choose the next agent to play, do not execute any task for example code generating yourself.
                        Think in the user's language.
                        Read the conversation and think if we already finish the task, if yes, next_step: TERMINATE, if not, choose the next agent to play.
                        If you think we already finish the task, please return the "next_step" valued: TERMINATE and leave the "name" empty.
                        If you sure that the next agent is the last one and it can finish the task, please return the "next_step" valued: TERMINATE and place the agent name in the key "name".
                        If you are re-planning because of some reason, please include the reason information in the query for the next agent.
                        Return a json that contain your planning steps and current step and the agent's name and a string query generated for the selected agent, for example:
                        {"planning": "1. step1; 2. step2", "next_step": "TERMINATE", "name": "order-expert-agent", "query": "list the user's most recent orders"}.
                        Only return the json, do not print anything else.
                        Always remember the goal and the agents list.
                        Here is the agent list:
                        """, goal, AgentGroup.AgentsInfo.agentsInfo(agents)))
                .promptTemplate(Strings.format("""
                        {}
                        Previous agent output/raw input:
                        """, contextVariableTemplate == null ? "" : contextVariableTemplate))
                .formatter(new DefaultJsonFormatter(true))
                .model(model)
                .llmProvider(llmProvider).build();
    }
}
