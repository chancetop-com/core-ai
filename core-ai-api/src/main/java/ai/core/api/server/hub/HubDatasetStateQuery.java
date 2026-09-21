package ai.core.api.server.hub;

import core.framework.api.web.service.QueryParam;

/**
 * Optional projection for {@code state.get}: comma-separated top-level field names.
 *
 * @author stephen
 */
public class HubDatasetStateQuery {
    @QueryParam(name = "fields")
    public String fields;
}
