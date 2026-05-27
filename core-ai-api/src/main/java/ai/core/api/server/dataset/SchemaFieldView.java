package ai.core.api.server.dataset;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SchemaFieldView {
    @Property(name = "name")
    public String name;

    @Property(name = "type")
    public String type;

    @Property(name = "label")
    public String label;
}
