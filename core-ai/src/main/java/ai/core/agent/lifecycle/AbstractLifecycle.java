package ai.core.agent.lifecycle;

import ai.core.agent.AgentBuilder;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;

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
    public void afterAgentBuild(AgentBuilder agentBuilder) {

    }

    @Override
    public void beforeModel(CompletionRequest completionRequest) {

    }

    @Override
    public void afterModel(CompletionResponse completionResponse) {

    }

    @Override
    public StreamingCallback afterModelStream(StreamingCallback callback) {
        return callback;
    }
}
