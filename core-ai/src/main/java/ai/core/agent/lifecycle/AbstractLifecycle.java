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
 * date: 2025/11/10
 * description: AbstractLifecycle, control subclass action
 */
public abstract class AbstractLifecycle {
    /**
     * at build stage
     * beforeAgentBuild  Do something
     *
     * @param agentBuilder agentBuilder
     */
    public void beforeAgentBuild(AgentBuilder agentBuilder) {

    }
    /**
     * at build stage
     * afterAgentBuild Do something
     *
     * @param agent agentBuilder
     */
    public void afterAgentBuild(Agent agent) {

    }

    public void beforeModel(CompletionRequest completionRequest, ExecutionContext executionContext) {

    }

    public void afterModel(CompletionRequest completionRequest, CompletionResponse completionResponse, ExecutionContext executionContext) {

    }
    /**
     * at runtime stage
     * beforeAgentRun Do something
     *
     * @param query            user query
     * @param executionContext agen runtime context
     */
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {

    }
    /**
     * at runtime stage
     * afterAgentRun Do something
     *
     * @param query            user query
     * @param result           agent run result(str)
     * @param executionContext agen runtime context
     */
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext executionContext) {

    }
    /**
     * at runtime stage
     * afterAgentFailed Do something
     *
     * @param query            user query
     * @param executionContext agen runtime context
     * @param exception        ex
     */
    public void afterAgentFailed(String query, ExecutionContext executionContext, Exception exception) {

    }

    public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
        // todo human in loop

    }

    public void afterTool(FunctionCall functionCall, ExecutionContext executionContext, ToolCallResult toolResult) {

    }
}
