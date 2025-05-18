package ai.core.api.mcp.schema.root;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListRootsResult {
    @Property(name = "roots")
    public List<Root> roots;
}
