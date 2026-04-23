package ai.core.api.server.agent;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListAgentsRequest {
    @QueryParam(name = "my")
    public String myAgents;
}
