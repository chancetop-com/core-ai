package ai.core.api.server.skillhub;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class SkillHubResourceRequest {
    @QueryParam(name = "path")
    public String path;
}
