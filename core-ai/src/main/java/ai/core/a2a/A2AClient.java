package ai.core.a2a;

import ai.core.api.a2a.AgentCard;
import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.GetTaskRequest;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.Task;

import java.util.concurrent.Flow;

/**
 * Consumer-side contract for calling a remote A2A-compatible agent.
 *
 * @author xander
 */
public interface A2AClient extends AutoCloseable {
    AgentCard getAgentCard();

    A2AInvocationResult send(SendMessageRequest request);

    Flow.Publisher<A2AStreamEvent> stream(SendMessageRequest request);

    Task getTask(GetTaskRequest request);

    Task cancelTask(CancelTaskRequest request);

    @Override
    default void close() {
    }
}
