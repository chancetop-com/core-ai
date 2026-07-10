package ai.core.api.server;

import ai.core.api.server.skill.CreateMarketplaceRepoRequest;
import ai.core.api.server.skill.ListSkillsRequest;
import ai.core.api.server.skill.ListSkillsResponse;
import ai.core.api.server.skill.MarketplaceListResponse;
import ai.core.api.server.skill.MarketplaceRepoDetailResponse;
import ai.core.api.server.skill.MarketplaceRepoView;
import ai.core.api.server.skill.RegisterRepoSkillRequest;
import ai.core.api.server.skill.RegisterRepoSkillsResponse;
import ai.core.api.server.skill.SkillDefinitionView;
import ai.core.api.server.skill.SkillDownloadResponse;
import ai.core.api.server.skill.UpdateSkillRequest;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface SkillWebService {
    @POST
    @Path("/api/skills/repo")
    RegisterRepoSkillsResponse registerFromRepo(RegisterRepoSkillRequest request);

    @GET
    @Path("/api/skills")
    ListSkillsResponse list(ListSkillsRequest request);

    @GET
    @Path("/api/skills/:id")
    SkillDefinitionView get(@PathParam("id") String id);

    @PUT
    @Path("/api/skills/:id")
    SkillDefinitionView update(@PathParam("id") String id, UpdateSkillRequest request);

    @DELETE
    @Path("/api/skills/:id")
    void delete(@PathParam("id") String id);

    @POST
    @Path("/api/skills/:id/sync")
    SkillDefinitionView syncFromRepo(@PathParam("id") String id);

    @GET
    @Path("/api/skills/:id/download")
    SkillDownloadResponse download(@PathParam("id") String id);

    @POST
    @Path("/api/skills/marketplace")
    MarketplaceRepoView createMarketplaceRepo(CreateMarketplaceRepoRequest request);

    @GET
    @Path("/api/skills/marketplace")
    MarketplaceListResponse marketplace();

    @GET
    @Path("/api/skills/marketplace/:repoId")
    MarketplaceRepoDetailResponse marketplaceRepo(@PathParam("repoId") String repoId);

    @POST
    @Path("/api/skills/marketplace/:repoId/install")
    RegisterRepoSkillsResponse installMarketplaceRepo(@PathParam("repoId") String repoId);

    @DELETE
    @Path("/api/skills/marketplace/:repoId")
    void deleteMarketplaceRepo(@PathParam("repoId") String repoId);
}
