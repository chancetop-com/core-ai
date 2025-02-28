package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.agent.AgentGroup;
import ai.core.agent.formatter.formatters.DefaultJsonFormatter;
import ai.core.llm.LLMProvider;
import core.framework.util.Strings;

/**
 * @author stephen
 */
public class DefaultModeratorAgent {
    public static Agent of(AgentGroup group, LLMProvider llmProvider) {
        return Agent.builder()
                .name("moderator-agent")
                .description("moderator of a role play game to solve task by guide the conversation and choose the next speaker agent")
                .systemPrompt(Strings.format("""
                        You are in a role play game to solve task by guide the conversation and choose the next speaker agent, the goal is:
                        {}.
                        The following agents list are available:
                        {}.
                        You need carefully review the capabilities of each agent, including the inputs and outputs of their functions to planning the conversation and choose the next agent to play.
                        Read the conversation, then select the next agent from the agents list to play.
                        Please do not make decisions for the user. Leave it to the user-input-agent to handle.
                        Return a json that contain the agent's name and a query generated for the selected agent.
                        Read the conversation. Then select the next agent from the agents list to play.
                        Please generate the detailed query for the next step, including all necessary context.
                        If you think we already finish the task, please return the next_step valued: TERMINATE.
                        Return a json that contain your planning steps and current step and the agent's name and a string query generated for the selected agent, for example:
                        {"planning": "1. step1; 2. step2", "next_step": "step2", "name": "order-expert-agent", "query": "list the user's most recent orders"}.
                        Only return the json, do not print anything else.
                        """, group.getDescription(), group.toString()))
                .promptTemplate("""
                        Previous agent output/raw input:
                        """)
                .llmProvider(llmProvider)
                .formatter(new DefaultJsonFormatter()).build();
    }
}
