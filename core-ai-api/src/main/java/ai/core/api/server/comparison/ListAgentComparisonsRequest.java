package ai.core.api.server.comparison;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListAgentComparisonsRequest {
    @QueryParam(name = "agent_id")
    public String agentId;
}
