package ai.core.agent.lifecycle;


import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FunctionCall;

import java.util.concurrent.atomic.AtomicReference;

/**
 * author: lim chen
 * date: 2025/11/7
 * description: Control the  whole lifecycle of the agent
 */
public interface AgentLifecycle {
    /**
     * at build stage
     * beforeAgentBuild  Do something
     *
     * @param agentBuilder agentBuilder
     */
    void beforeAgentBuild(AgentBuilder agentBuilder);

    /**
     * at build stage
     * afterAgentBuild Do something
     *
     * @param agent agentBuilder
     */
    void afterAgentBuild(Agent agent);

    /**
     * at runtime stage
     * beforeAgentRun Do something
     *
     * @param query            user query
     * @param executionContext agen runtime context
     */
    void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext);

    /**
     * at runtime stage
     * afterAgentRun Do something
     *
     * @param result           agent run result(str)
     * @param executionContext agen runtime context
     */
    void afterAgentRun(AtomicReference<String> result, ExecutionContext executionContext);

    /**
     * at runtime stage
     * afterAgentFailed Do something
     *
     * @param query            user query
     * @param executionContext agen runtime context
     * @param exception        ex
     */
    void afterAgentFailed(String query, ExecutionContext executionContext, Exception exception);

    void beforeModel(CompletionRequest completionRequest, ExecutionContext executionContext);

    void afterModel(CompletionResponse completionResponse, ExecutionContext executionContext);

    void beforeTool(FunctionCall functionCall, ExecutionContext executionContext);

    void afterTool(AtomicReference<String> result, ExecutionContext executionContext);


}
