package ai.core.api.server.channel;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author stephen
 */
public interface ChannelWebService {
    @GET
    @Path("/api/admin/channels")
    ListChannelsResponse list();

    @POST
    @Path("/api/admin/channels")
    @ResponseStatus(HTTPStatus.CREATED)
    ChannelResponse create(ChannelConfigRequest request);

    @GET
    @Path("/api/admin/channels/:channelId")
    ChannelResponse get(@PathParam("channelId") String channelId);

    @PUT
    @Path("/api/admin/channels/:channelId")
    ChannelResponse update(@PathParam("channelId") String channelId, ChannelConfigRequest request);

    @DELETE
    @Path("/api/admin/channels/:channelId")
    void delete(@PathParam("channelId") String channelId);

    @GET
    @Path("/api/admin/channel-types")
    ListChannelTypesResponse types();
}
