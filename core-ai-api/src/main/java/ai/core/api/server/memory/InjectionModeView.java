package ai.core.api.server.memory;

import core.framework.api.json.Property;

/**
 * JSON view mirror of the memory injection mode enum. Separate from the entity enum
 * because core-ng forbids @MongoEnumValue and @Property on the same class.
 *
 * @author Xander
 */
public enum InjectionModeView {
    @Property(name = "layered")
    LAYERED,
    @Property(name = "full")
    FULL
}
