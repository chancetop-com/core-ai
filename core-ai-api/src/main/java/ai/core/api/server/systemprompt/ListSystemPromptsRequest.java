package ai.core.api.server.systemprompt;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListSystemPromptsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
