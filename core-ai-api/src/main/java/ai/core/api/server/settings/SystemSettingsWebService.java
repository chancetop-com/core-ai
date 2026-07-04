package ai.core.api.server.settings;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface SystemSettingsWebService {
    @GET
    @Path("/api/admin/system-settings")
    SystemSettingsView get();

    @PUT
    @Path("/api/admin/system-settings")
    SystemSettingsView update(SystemSettingsRequest request);
}
