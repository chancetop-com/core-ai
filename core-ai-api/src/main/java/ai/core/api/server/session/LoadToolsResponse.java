package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class LoadToolsResponse {
    @Property(name = "loaded_tools")
    public List<String> loadedTools;
}
