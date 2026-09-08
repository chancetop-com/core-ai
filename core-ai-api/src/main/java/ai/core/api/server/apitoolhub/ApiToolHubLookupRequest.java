package ai.core.api.server.apitoolhub;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ApiToolHubLookupRequest {
    @QueryParam(name = "tool_name")
    public String toolName;
}
