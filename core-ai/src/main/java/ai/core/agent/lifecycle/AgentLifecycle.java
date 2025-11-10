package ai.core.agent.lifecycle;


import ai.core.agent.AgentBuilder;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;

/**
 * author: lim chen
 * date: 2025/11/7
 * description:
 */
public interface AgentLifecycle {
    void beforeAgentBuild(AgentBuilder agentBuilder);

    void afterAgentBuild(AgentBuilder agentBuilder);

    void beforeModel(CompletionRequest completionRequest);

    void afterModel(CompletionResponse completionResponse);

    StreamingCallback afterModelStream(StreamingCallback callback);
}
