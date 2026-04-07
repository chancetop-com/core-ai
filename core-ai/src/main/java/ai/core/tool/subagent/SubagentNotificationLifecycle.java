package ai.core.tool.subagent;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;

import java.util.List;

/**
 * Lifecycle that drives background subagent completion notifications.
 * Drains completed tasks before each turn and keeps the chatTurns loop alive while tasks are pending.
 */
public class SubagentNotificationLifecycle extends AbstractLifecycle {

    @Override
    public List<Message> beforeTurn(ExecutionContext context) {
        var registry = context.getSubagentTaskRegistry();
        if (registry == null) return List.of();
        return registry.drainCompleted().stream()
                .map(this::buildNotificationMessage)
                .toList();
    }

    @Override
    public boolean shouldContinueTurns(ExecutionContext context) {
        var registry = context.getSubagentTaskRegistry();
        return registry != null && registry.hasPending();
    }

    private Message buildNotificationMessage(SubagentTaskRegistry.SubagentResult result) {
        var resultXml = result.status().equals("completed")
                ? "<result>" + result.result() + "</result>"
                : "<error>" + result.error() + "</error>";
        var outputRefXml = result.outputRef() != null ? "<output-ref>" + result.outputRef() + "</output-ref>\n" : "";
        var xml = """
                <task-notification>
                <task-id>%s</task-id>
                <status>%s</status>
                %s%s
                </task-notification>
                """.formatted(result.taskId(), result.status(), outputRefXml, resultXml);
        return Message.of(RoleType.USER, xml);
    }
}
