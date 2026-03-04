package ai.core.api.server.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListToolCategoriesResponse {
    @Property(name = "categories")
    public List<String> categories;
}
