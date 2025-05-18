package ai.core.api.mcp.schema.resource;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ResourceTemplate {
    @Property(name = "uriTemplate")
    public String uriTemplate;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "mimeType")
    public String mimeType;

    @Property(name = "annotations")
    public Annotations annotations;
}
