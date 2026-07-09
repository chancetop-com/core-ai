package ai.core.api.server.notification;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListNotificationsRequest {
    @QueryParam(name = "category")
    public String category;

    @QueryParam(name = "status")
    public String status;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
