package ai.core.api.server.mcphub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class HubToolsResponse {
    @Property(name = "tools")
    public List<HubToolSummary> tools;
}
