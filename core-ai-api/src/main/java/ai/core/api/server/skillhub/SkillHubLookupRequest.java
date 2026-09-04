package ai.core.api.server.skillhub;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class SkillHubLookupRequest {
    @QueryParam(name = "name")
    public String name;
}
