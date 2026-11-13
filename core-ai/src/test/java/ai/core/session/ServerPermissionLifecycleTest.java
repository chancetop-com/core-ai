package ai.core.session;

import ai.core.agent.ExecutionContext;
import ai.core.agent.subagent.SubagentModelRef;
import ai.core.agent.subagent.SubagentThreadKind;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.llm.domain.FunctionCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The forwarded tool_start event carries the tool's origin (sourceType) as toolType — clients group
 * tool calls by it (the desktop lists subagent / mcp / api / agent calls under their own chip).
 *
 * @author stephen
 */
class ServerPermissionLifecycleTest {
    @Test
    void startEventCarriesTheToolSourceType() {
        var events = new ArrayList<AgentEvent>();
        var lifecycle = new ServerPermissionLifecycle("s-1", events::add, null, true, null,
                toolName -> "task".equals(toolName) ? "subagent" : "builtin");

        lifecycle.beforeTool(FunctionCall.of("call-1", "function", "task", "{}"), context());

        var event = (ToolStartEvent) events.getFirst();
        assertEquals("subagent", event.toolType);
    }

    @Test
    void startEventLeavesToolTypeUnsetWithoutAResolver() {
        var events = new ArrayList<AgentEvent>();
        var lifecycle = new ServerPermissionLifecycle("s-1", events::add, null, true, null, null);

        lifecycle.beforeTool(FunctionCall.of("call-1", "function", "read_file", "{}"), context());

        var event = (ToolStartEvent) events.getFirst();
        assertNull(event.toolType);
    }

    @Test
    void startEventUsesTheExplicitAndThreadModel() {
        var events = new ArrayList<AgentEvent>();
        var lifecycle = new ServerPermissionLifecycle("s-1", events::add, null, true, null, null);
        var context = context();
        context.getSubagentThreads().registerWithModel("sa-1", SubagentThreadKind.TASK, "general-purpose-agent", "d",
                new SubagentModelRef("m-thread", "p1"), null);

        lifecycle.beforeTool(FunctionCall.of("call-1", "function", "task",
                "{\"subagent_type\":\"general-purpose-agent\",\"task_id\":\"sa-1\",\"model\":\"m-call\"}"), context);
        assertEquals("m-call", ((ToolStartEvent) events.get(0)).model);

        events.clear();
        lifecycle.beforeTool(FunctionCall.of("call-2", "function", "task", "{\"task_id\":\"sa-1\"}"), context);
        assertEquals("m-thread", ((ToolStartEvent) events.get(0)).model);
    }

    private ExecutionContext context() {
        return ExecutionContext.builder().sessionId("s-1").build();
    }
}
