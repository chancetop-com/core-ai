package ai.core.agent.lifecycle;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;
import ai.core.agent.ExecutionContext;
import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Usage;

import java.util.concurrent.atomic.AtomicReference;

/**
 * author: lim chen
 * date: 2025/11/17
 * description: Because check cannot have public methods/class.
 */
public class FakerLifecycle {
    @CoreAiMethod(description = "query_person", name = "query_person")
    public String queryPerson(@CoreAiParameter(name = "name", description = "", required = true) String name) {
        return name;
    }


    public static class FakerLifecycleInner extends AbstractLifecycle {

        @Override
        public void afterTool(AtomicReference<String> result, ExecutionContext executionContext) {
            result.set(result.get() + "_mock_tool_result");
        }

        @Override
        public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
            functionCall.function.arguments = "{\"name\":\"mock_name\"}";
        }


        @Override
        public void afterAgentRun(AtomicReference<String> result, ExecutionContext executionContext) {
            result.set(result.get() + "mock_test_result");
        }

        @Override
        public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {
            query.set(query.get() + "mock_query");
        }

        @Override
        public void afterModel(CompletionResponse completionResponse, ExecutionContext executionContext) {
            completionResponse.usage = new Usage(1, 1, 1);
        }

        @Override
        public void beforeModel(CompletionRequest completionRequest, ExecutionContext executionContext) {
            completionRequest.model = "mock_model";
        }

        @Override
        public void afterAgentBuild(Agent agent) {
            agent.setSystemPrompt("mock_system_prompt");
        }

        @Override
        public void beforeAgentBuild(AgentBuilder agentBuilder) {
            agentBuilder.maxRound(1000);
        }
    }
}
