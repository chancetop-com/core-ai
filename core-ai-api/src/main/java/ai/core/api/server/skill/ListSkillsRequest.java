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

    @QueryParam(name = "user_id")
    public String userId;

    @QueryParam(name = "q")
    public String query;

    @QueryParam(name = "search_in")
    public String searchIn;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
