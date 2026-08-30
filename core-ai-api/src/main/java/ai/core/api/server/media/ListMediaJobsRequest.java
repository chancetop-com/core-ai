package ai.core.api.server.media;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListMediaJobsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;

    @QueryParam(name = "mediaType")
    public String mediaType;

    @QueryParam(name = "costSource")
    public String costSource;

    @QueryParam(name = "userId")
    public String userId;
}
