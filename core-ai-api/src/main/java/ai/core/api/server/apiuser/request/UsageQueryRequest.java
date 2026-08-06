package ai.core.api.server.apiuser.request;

import core.framework.api.validate.NotNull;
import core.framework.api.web.service.QueryParam;

/**
 * @author core-ai
 */
public class UsageQueryRequest {
    @NotNull
    @QueryParam(name = "from")
    public String from;

    @NotNull
    @QueryParam(name = "to")
    public String to;
}
