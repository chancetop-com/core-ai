package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class SsePlanUpdateEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "todos")
    public List<TodoItem> todos;

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
