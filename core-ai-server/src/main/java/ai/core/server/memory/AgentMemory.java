package ai.core.server.memory;

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
    // Layer constants for V2 three-tier memory architecture
    public static final String LAYER_KNOWLEDGE = "knowledge";     // Layer 1: auto-injected (DOMAIN_KNOWLEDGE, GOTCHA)
    public static final String LAYER_METHODS = "methods";         // Layer 2: on-demand (WORKFLOW_PATTERN, TOOL_USAGE, EFFICIENCY)
    public static final String LAYER_TRAJECTORIES = "trajectories"; // Layer 3: append-only session summaries

    @Id
    public String id;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "type")
    public String type;

    @Field(name = "layer")
    public String layer;

    @Field(name = "content")
    public String content;

    @Field(name = "source_trace_ids")
    public List<String> sourceTraceIds;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
