package ai.core.api.server.skill;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListSkillsRequest {
    @QueryParam(name = "namespace")
    public String namespace;

    @QueryParam(name = "source_type")
    public String sourceType;

    @QueryParam(name = "q")
    public String query;
}
