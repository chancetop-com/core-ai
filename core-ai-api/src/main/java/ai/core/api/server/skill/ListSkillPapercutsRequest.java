package ai.core.api.server.skill;

import core.framework.api.web.service.QueryParam;

/**
 * Query parameters for GET /api/skills/:id/papercuts.
 *
 * @author stephen
 */
public class ListSkillPapercutsRequest {
    @QueryParam(name = "status")
    public String status;
}
