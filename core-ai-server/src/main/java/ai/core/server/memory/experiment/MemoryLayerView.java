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
    knowledge,
    @Property(name = "methods")
    methods,
    @Property(name = "trajectories")
    trajectories;

    public static MemoryLayerView from(MemoryLayer layer) {
        if (layer == null) return knowledge;
        return valueOf(layer.name());
    }

    public MemoryLayer toEntity() {
        return MemoryLayer.valueOf(name());
    }
}
