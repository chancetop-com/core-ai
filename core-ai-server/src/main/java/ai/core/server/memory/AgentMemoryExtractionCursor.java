package ai.core.server.memory;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
@Collection(name = "agent_memory_extraction_cursors")
public class AgentMemoryExtractionCursor {
    @Id
    public String id;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "last_processed_at")
    public ZonedDateTime lastProcessedAt;

    @Field(name = "last_trace_ids")
    public List<String> lastTraceIds;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
