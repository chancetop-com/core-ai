package ai.core.api.server;

import ai.core.api.server.artifact.ListMyArtifactsRequest;
import ai.core.api.server.artifact.ListMyArtifactsResponse;
import ai.core.api.server.artifact.ListSharedArtifactsRequest;
import ai.core.api.server.artifact.ListSharedArtifactsResponse;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

public interface ArtifactWebService {
    @GET
    @Path("/api/artifacts/my")
    ListMyArtifactsResponse listMy(ListMyArtifactsRequest request);

    @GET
    @Path("/api/artifacts/shared")
    ListSharedArtifactsResponse listShared(ListSharedArtifactsRequest request);
}
