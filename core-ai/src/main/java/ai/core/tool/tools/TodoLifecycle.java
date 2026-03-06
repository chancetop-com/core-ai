package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.prompt.SystemVariables;
import core.framework.json.JSON;

import java.util.concurrent.atomic.AtomicReference;

public class TodoLifecycle extends AbstractLifecycle {
    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext context) {
        var todos = WriteTodosTool.loadTodos(context);
        if (todos.isEmpty()) return;
        context.getCustomVariables().put(WriteTodosTool.TODOS_CONTEXT_KEY, todos);
        context.getCustomVariables().put(SystemVariables.AGENT_WRITE_TODOS_SYSTEM_PROMPT, JSON.toJSON(todos));
    }
}
