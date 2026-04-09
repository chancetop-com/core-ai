package ai.core.agent.lifecycle;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.TaskCompletedEvent;
import ai.core.api.server.session.TaskStartEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class TaskLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskLifecycle.class);
    private final Consumer<AgentEvent> dispatcher;

    public TaskLifecycle(Consumer<AgentEvent> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext context) {
        if (!context.isSubagent()) return;
        LOGGER.info("subagent task start, taskId={}, query={}", context.getTaskId(), query.get());
        dispatcher.accept(TaskStartEvent.of(context.getSessionId(), context.getTaskId(), context.getTaskName()));
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext context) {
        if (!context.isSubagent()) return;
        LOGGER.info("subagent task completed, taskId={}", context.getTaskId());
        dispatcher.accept(TaskCompletedEvent.of(context.getSessionId(), context.getTaskId(), context.getTaskName(), "success", result.get()));
    }

    @Override
    public void afterAgentFailed(String query, ExecutionContext context, Exception exception) {
        if (!context.isSubagent()) return;
        LOGGER.info("subagent task failed, taskId={}", context.getTaskId());
        dispatcher.accept(TaskCompletedEvent.of(context.getSessionId(), context.getTaskId(), context.getTaskName(), "failed", exception.getMessage()));
    }
}
