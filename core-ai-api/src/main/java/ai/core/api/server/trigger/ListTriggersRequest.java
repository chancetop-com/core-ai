package ai.core.api.server.trigger;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListTriggersRequest {
    @QueryParam(name = "type")
    public String type;
}
