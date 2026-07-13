package ai.core.server.memory.experiment;

import core.framework.mongo.MongoEnumValue;

/**
 * Three-tier memory layer.
 *
 * @author stephen
 */
public enum MemoryLayer {
    @MongoEnumValue("knowledge")
    KNOWLEDGE,
    @MongoEnumValue("methods")
    METHODS,
    @MongoEnumValue("trajectories")
    TRAJECTORIES;

    public String mongoValue() {
        return switch (this) {
            case KNOWLEDGE -> "knowledge";
            case METHODS -> "methods";
            case TRAJECTORIES -> "trajectories";
        };
    }
}
