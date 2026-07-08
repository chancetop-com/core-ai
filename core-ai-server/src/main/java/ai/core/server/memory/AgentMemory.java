package ai.core.server.memory;

import ai.core.server.memory.experiment.MemoryLayer;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
@Collection(name = "agent_memories")
public class AgentMemory {
    @Id
    public String id;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "type")
    public String type;

    @Field(name = "layer")
    public MemoryLayer layer;

    @Field(name = "content")
    public String content;

    @Field(name = "source_trace_ids")
    public List<String> sourceTraceIds;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
