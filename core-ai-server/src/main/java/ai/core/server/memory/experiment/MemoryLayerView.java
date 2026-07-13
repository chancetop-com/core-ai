package ai.core.server.memory.experiment;

import core.framework.api.json.Property;

/**
 * JSON view mirror of {@link MemoryLayer}. Separate from the entity enum
 * because core-ng forbids @MongoEnumValue and @Property on the same class.
 *
 * @author stephen
 */
public enum MemoryLayerView {
    @Property(name = "knowledge")
    KNOWLEDGE,
    @Property(name = "methods")
    METHODS,
    @Property(name = "trajectories")
    TRAJECTORIES;

    public static MemoryLayerView from(MemoryLayer layer) {
        if (layer == null) return KNOWLEDGE;
        return valueOf(layer.name());
    }

    public MemoryLayer toEntity() {
        return MemoryLayer.valueOf(name());
    }
}
