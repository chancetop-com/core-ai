package ai.core.session;

import ai.core.agent.ExecutionContext;
import ai.core.agent.subagent.SubagentModelRef;
import ai.core.agent.subagent.SubagentThreadKind;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;
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

    @Test
    void subagentCallsNameTheToolCallThatSpawnedThem() {
        var events = new ArrayList<AgentEvent>();
        var lifecycle = new ServerPermissionLifecycle("s-1", events::add, null, true, null, null);
        var subContext = ExecutionContext.builder()
                .sessionId("subagent:explore-1")
                .parentCallId("call-task-1")
                .build();
        var call = FunctionCall.of("call-2", "function", "read_file", "{\"file_path\":\"a.md\"}");

        lifecycle.beforeTool(call, subContext);
        lifecycle.afterTool(call, subContext, ToolCallResult.completed("ok"));

        assertEquals("call-task-1", ((ToolStartEvent) events.get(0)).parentCallId);
        assertEquals("call-task-1", ((ToolResultEvent) events.get(1)).parentCallId);
    }

    @Test
    void topLevelCallsHaveNoParent() {
        var events = new ArrayList<AgentEvent>();
        var lifecycle = new ServerPermissionLifecycle("s-1", events::add, null, true, null, null);
        var call = FunctionCall.of("call-1", "function", "read_file", "{\"file_path\":\"a.md\"}");

        lifecycle.beforeTool(call, context());

        assertNull(((ToolStartEvent) events.getFirst()).parentCallId);
    }

    private ExecutionContext context() {
        return ExecutionContext.builder().sessionId("s-1").build();
    }
}
