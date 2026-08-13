package ai.core.api.server.trace;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class GenerationsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;

    @QueryParam(name = "model")
    public String model;
}
