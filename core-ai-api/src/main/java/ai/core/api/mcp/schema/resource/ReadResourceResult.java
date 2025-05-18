package ai.core.api.mcp.schema.resource;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ReadResourceResult {
    @Property(name = "contents")
    public List<ResourceContents> contents;
}
