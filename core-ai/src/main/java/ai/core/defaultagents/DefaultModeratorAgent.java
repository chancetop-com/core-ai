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
    public static Agent of(LLMProvider llmProvider, String model, String goal, List<Node<?>> agents) {
        return of(llmProvider, model, goal, agents, "", "");
    }
    public static Agent of(LLMProvider llmProvider, String model, String goal, List<Node<?>> agents, String contextVariableTemplate) {
        return of(llmProvider, model, goal, agents, "", contextVariableTemplate);
    }
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
    public static Agent of(LLMProvider llmProvider, String model, String goal, List<Node<?>> agents, String additionSystemPrompt, String contextVariableTemplate) {
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
                        Because the next agent cannot read the whole conversation, please generate the detailed query for the next agent, including all necessary context that next agent might need.
                        You only do the planning and choose the next agent to play, do not execute any task for example code generating yourself.
                        Read the conversation and think if we already finish the task, if yes, next_step_action: TERMINATE, if not, choose the next agent to play.
                        If you think we already finish the task, please return the "next_step_action" valued: TERMINATE and leave the "next_agent_name" empty.
                        If you sure that the next agent is the last one and it can finish the task, please return the "next_step_action" valued: TERMINATE and place the agent name in the key "next_agent_name".
                        If you are re-planning because of some reason, please include the reason information in the query for the next agent.
                        Return a json at the end of your output that contain your planning steps and the agent's name and a string query generated for the selected agent, for example:
                        {
                            "planning": "1. step1; 2. step2; 3. step3. next step we are going to fetch the user's information",
                            "next_step_action": "TERMINATE",
                            "next_agent_name": "order-expert-agent",
                            "next_query": "list the user's most recent orders"
                        }.
                        Please clearly indicate the next step in your planning.
                        You need to carefully analyze the output of the previous round of the agent to determine whether it was an error, a failure, or a success.
                        Always remember the goal and the agents list.
                        {}
                        """, goal, AgentGroup.AgentsInfo.agentsInfo(agents), additionSystemPrompt))
                .promptTemplate(Strings.format("""
                        {}
                        Previous agent output/raw input:
                        """, contextVariableTemplate == null ? "" : contextVariableTemplate))
                .formatter(new DefaultJsonFormatter(true))
                .model(model)
                .llmProvider(llmProvider).build();
    }
}
