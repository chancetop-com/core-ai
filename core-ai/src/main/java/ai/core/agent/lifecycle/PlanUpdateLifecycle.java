package ai.core.agent.lifecycle;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class PlanUpdateLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlanUpdateLifecycle.class);

    private final Consumer<PlanUpdateEvent> dispatcher;

    public PlanUpdateLifecycle(Consumer<PlanUpdateEvent> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext executionContext, ToolCallResult toolResult) {
        if (!"write_todos".equals(functionCall.function.name) || !toolResult.isCompleted()) {
            return;
        }

        try {
            List<PlanUpdateEvent.TodoItem> todos = parseTodos(functionCall.function.arguments);
            PlanUpdateEvent event = PlanUpdateEvent.of(executionContext.getSessionId(), todos);
            dispatcher.accept(event);
        } catch (Exception e) {
            LOGGER.warn("failed to parse write_todos arguments, sessionId={}, error={}",
                    executionContext.getSessionId(), e.getMessage());
        }
    }

    private List<PlanUpdateEvent.TodoItem> parseTodos(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return List.of();
        }

        var trimmed = arguments.trim();
        if (trimmed.startsWith("[")) {
            var listType = new TypeReference<List<PlanUpdateEvent.TodoItem>>() {

            }.getType();
            return JSON.fromJSON(listType, trimmed);
        }

        // Handle wrapped JSON object: {"todos": [...]}
        try {
            var mapType = new TypeReference<Map<String, Object>>() {

            }.getType();
            Map<String, Object> map = JSON.fromJSON(mapType, trimmed);
            var todosObj = map.get("todos");
            if (todosObj instanceof List<?> list) {
                List<PlanUpdateEvent.TodoItem> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        result.add(new PlanUpdateEvent.TodoItem(
                                (String) m.get("content"),
                                (String) m.get("status")));
                    }
                }
                return result;
            }
        } catch (Exception e) {
            LOGGER.debug("failed to parse wrapped todos object: {}", e.getMessage());
        }

        return List.of();
    }
}
