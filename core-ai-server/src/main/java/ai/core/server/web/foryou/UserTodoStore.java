package ai.core.server.web.foryou;

import ai.core.server.domain.UserTodo;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface UserTodoStore {
    Optional<UserTodo> get(String id);

    List<UserTodo> list(String userId);

    List<UserTodo> active(String userId);

    long count(String userId);

    long activeCount(String userId);

    void insert(UserTodo todo);

    boolean insertIfAbsent(UserTodo todo);

    void replace(UserTodo todo);

    void delete(String id);
}
