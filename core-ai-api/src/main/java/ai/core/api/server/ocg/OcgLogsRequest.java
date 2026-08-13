package ai.core.api.server.ocg;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class OcgLogsRequest {
    @QueryParam(name = "type")
    public String type;

    @QueryParam(name = "tail")
    public Integer tail;
}
