package ai.core.api.server.prompt;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListPromptsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
