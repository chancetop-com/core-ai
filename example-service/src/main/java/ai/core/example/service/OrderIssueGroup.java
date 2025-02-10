package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.agent.AgentGroup;
import ai.core.agent.UserInputAgent;
import ai.core.llm.LLMProvider;
import ai.core.persistence.PersistenceProvider;
import ai.core.tool.function.Functions;

import java.util.List;

/**
 * @author stephen
 */
public class OrderIssueGroup {
    public static AgentGroup create(LLMProvider liteLLMProvider, PersistenceProvider persistenceProvider, UserInfoService userInfoService) {
        var userInfoAgent = Agent.builder()
                .name("user-info-agent")
                .description("agent that know all about user")
                .systemPrompt("You are an assistant help the system to fetch the user's information.")
                .promptTemplate("Query:")
                .llmProvider(liteLLMProvider)
                .toolCalls(Functions.from(userInfoService, "getUserInfo")).build();
        var orderAgent = Agent.builder()
                .name("order-agent")
                .description("agent that know all about order")
                .systemPrompt("You are an assistant that helps the system to fetch the user's orders.")
                .promptTemplate("Query:")
                .llmProvider(liteLLMProvider)
                .toolCalls(Functions.from(userInfoService, "getUserLastOrder")).build();
        var issueAgent = Agent.builder()
                .name("order-issue-agent")
                .description("agent that help user to create order issue")
                .systemPrompt("You are an assistant that helps users create order issues. You need to ask users for the necessary information to create an order issue if that information is not provide in the query.")
                .promptTemplate("Query:")
                .llmProvider(liteLLMProvider)
                .toolCalls(Functions.from(userInfoService, "createOrderIssue")).build();
        var userAgent = UserInputAgent.builder().persistenceProvider(persistenceProvider).build();
        return AgentGroup.builder()
                .name("order-issue-agent-group")
                .description("you are an agent group that help user to create order issue.")
                .agents(List.of(userInfoAgent, orderAgent, issueAgent, userAgent))
                .maxRound(10)
                .persistenceProvider(persistenceProvider)
                .llmProvider(liteLLMProvider).build();
    }
}
