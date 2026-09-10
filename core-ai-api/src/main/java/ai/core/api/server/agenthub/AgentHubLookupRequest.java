package ai.core.api.server.agenthub;

import core.framework.api.web.service.QueryParam;

/**
 * Resolves an agent name to the visible agents carrying it. Agent names are unique per owner
 * only, so a name can be ambiguous and the caller has to fall back to the id.
 *
 * @author stephen
 */
public class AgentHubLookupRequest {
    @QueryParam(name = "name")
    public String name;
}
