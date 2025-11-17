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
 * date: 2025/11/10
 * description: AbstractLifecycle, control subclass action
 */
public abstract class AbstractLifecycle implements AgentLifecycle {
    @Override
    public void beforeAgentBuild(AgentBuilder agentBuilder) {

    }

    @Override
    public void afterAgentBuild(Agent agent) {

    }

    @Override
    public void beforeModel(CompletionRequest completionRequest, ExecutionContext executionContext) {

    }

    @Override
    public void afterModel(CompletionResponse completionResponse, ExecutionContext executionContext) {

    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {

    }

    @Override
    public void afterAgentRun(AtomicReference<String> result, ExecutionContext executionContext) {

    }

    @Override
    public void afterAgentFailed(String query, ExecutionContext executionContext, Exception exception) {

    }

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {

    }

    @Override
    public void afterTool(AtomicReference<String> result, ExecutionContext executionContext) {

    }
}
