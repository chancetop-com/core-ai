package ai.core.api.server.artifact;

import core.framework.api.web.service.QueryParam;

public class ListSharedArtifactsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;

    @QueryParam(name = "name")
    public String name;

    @QueryParam(name = "user_id")
    public String userId;
}
