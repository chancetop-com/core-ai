package ai.core.agent.lifecycle;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;

import java.util.concurrent.atomic.AtomicReference;

/**
 * author: lim chen
 */
public abstract class AbstractLifecycle {
    public void beforeAgentBuild(AgentBuilder agentBuilder) {

    }

    public void afterAgentBuild(Agent agent) {

    }

    public void beforeModel(CompletionRequest completionRequest, ExecutionContext executionContext) {

    }

    public void afterModel(CompletionRequest completionRequest, CompletionResponse completionResponse, ExecutionContext executionContext) {

    }

    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {

    }

    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext executionContext) {

    }

    public void afterAgentFailed(String query, ExecutionContext executionContext, Exception exception) {

    }

    public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
        // todo human in loop

    }

    public void afterTool(FunctionCall functionCall, ExecutionContext executionContext, ToolCallResult toolResult) {

    }
}
