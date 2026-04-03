package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class PlanUpdateEvent implements AgentEvent {
    public static PlanUpdateEvent of(String sessionId, List<TodoItem> todos) {
        var event = new PlanUpdateEvent();
        event.sessionId = sessionId;
        event.todos = todos;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "todos")
    public List<TodoItem> todos;

    @Override
    public String sessionId() {
        return sessionId;
    }

    public static class TodoItem {
        @Property(name = "content")
        public String content;

        @Property(name = "status")
        public String status;

        public TodoItem() {
        }

        public TodoItem(String content, String status) {
            this.content = content;
            this.status = status;
        }
    }
}
