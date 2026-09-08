package ai.core.api.server.apitoolhub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ApiToolHubAppsResponse {
    @Property(name = "apps")
    public List<ApiToolHubAppView> apps;
}
