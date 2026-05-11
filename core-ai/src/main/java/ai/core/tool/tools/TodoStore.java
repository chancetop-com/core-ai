package ai.core.tool.tools;

import java.util.List;

/**
 * Persistence contract for Task V2 entities.
 * Implementations are provided by the application layer (CLI, server, etc.)
 * to supply the storage backend.
 *
 * @author lim chen
 */
public interface TodoStore {

    WriteTodoTaskTool.TaskEntity create(WriteTodoTaskTool.TaskEntity task);

    WriteTodoTaskTool.TaskEntity read(String taskId);

    void write(String taskId, WriteTodoTaskTool.TaskEntity task);

    void delete(String taskId);

    List<WriteTodoTaskTool.TaskEntity> listAll();

    int nextId();

    void cleanup();
}
