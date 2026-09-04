package ai.core.api.server.skillhub;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class SkillHubSearchRequest {
    @QueryParam(name = "query")
    public String query;

    @QueryParam(name = "namespace")
    public String namespace;

    @QueryParam(name = "source_type")
    public String sourceType;

    @QueryParam(name = "limit")
    public Integer limit;
}
