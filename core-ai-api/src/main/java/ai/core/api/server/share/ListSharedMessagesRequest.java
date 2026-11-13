package ai.core.api.server.share;

import core.framework.api.web.service.QueryParam;

/**
 * @author Xander
 */
public class ListSharedMessagesRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
