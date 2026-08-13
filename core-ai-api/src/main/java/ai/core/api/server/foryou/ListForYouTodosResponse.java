package ai.core.api.server.foryou;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListForYouTodosResponse {
    @Property(name = "todos")
    public List<ForYouTodoView> todos;
}
