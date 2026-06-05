package ai.core.api.server.artifact;

import core.framework.api.web.service.QueryParam;

public class ListMyArtifactsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
