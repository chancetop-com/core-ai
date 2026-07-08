package ai.core.server.memory.experiment;

import core.framework.mongo.MongoEnumValue;

/**
 * Three-tier memory layer.
 * Enum names match stored values for compatibility with existing data.
 *
 * @author stephen
 */
public enum MemoryLayer {
    @MongoEnumValue("knowledge")
    knowledge,       // Layer 1: auto-injected (DOMAIN_KNOWLEDGE, GOTCHA)
    @MongoEnumValue("methods")
    methods,         // Layer 2: on-demand (WORKFLOW_PATTERN, TOOL_USAGE, EFFICIENCY)
    @MongoEnumValue("trajectories")
    trajectories;    // Layer 3: append-only session summaries
}

