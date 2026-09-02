package ai.core.server.asynctask;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Persisted long-running tool call (see {@link ai.core.tool.ToolCallAsyncTaskManager}): the manager's
 * serialized task under its own key, so pending work survives a restart and a server tick can list it.
 *
 * @author stephen
 */
@Collection(name = "async_tool_tasks")
public class AsyncToolTaskRecord {
    @Id
    public String id;

    @Field(name = "data")
    public String data;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
