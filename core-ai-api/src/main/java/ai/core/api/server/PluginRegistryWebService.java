package ai.core.api.server;

import ai.core.api.server.plugin.PluginRegistryEntryView;
import ai.core.api.server.plugin.PluginRegistrySearchRequest;
import ai.core.api.server.plugin.PluginRegistrySearchResponse;
import ai.core.api.server.plugin.RegisterPluginRequest;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Desktop plugin registry: discovery, detail and publication of pinned plugin packages.
 *
 * @author stephen
 */
public interface PluginRegistryWebService {
    @GET
    @Path("/api/plugins")
    PluginRegistrySearchResponse search(PluginRegistrySearchRequest request);

    @GET
    @Path("/api/plugins/:id")
    PluginRegistryEntryView get(@PathParam("id") String id);

    @POST
    @Path("/api/plugins")
    PluginRegistryEntryView register(RegisterPluginRequest request);

    @DELETE
    @Path("/api/plugins/:id")
    void delete(@PathParam("id") String id);
}
