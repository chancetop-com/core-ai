package ai.core.api.server.memory;

import core.framework.api.json.Property;

/**
 * JSON view mirror of the memory layer enum. Separate from the entity enum
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
    TRAJECTORIES
}
