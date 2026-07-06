package ai.core.server.memory;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class AgentMemoryView {
    @Property(name = "id")
    public String id;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "type")
    public String type;

    @Property(name = "layer")
    public String layer;

    @Property(name = "content")
    public String content;

    @Property(name = "source_trace_ids")
    public List<String> sourceTraceIds;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
