package ai.core.api.server.settings;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;

/**
 * @author xander
 */
public interface SandboxSnapshotSettingsWebService {
    @GET
    @Path("/api/settings/sandbox-snapshot")
    SandboxSnapshotSettingsView get();

    @PUT
    @Path("/api/settings/sandbox-snapshot")
    SandboxSnapshotSettingsView update(SandboxSnapshotSettingsRequest request);
}
