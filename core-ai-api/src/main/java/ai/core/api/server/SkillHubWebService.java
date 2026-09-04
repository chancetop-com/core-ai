package ai.core.api.server;

import ai.core.api.server.skillhub.SkillHubDetail;
import ai.core.api.server.skillhub.SkillHubLookupRequest;
import ai.core.api.server.skillhub.SkillHubLookupResponse;
import ai.core.api.server.skillhub.SkillHubResourceRequest;
import ai.core.api.server.skillhub.SkillHubResourceResponse;
import ai.core.api.server.skillhub.SkillHubSearchRequest;
import ai.core.api.server.skillhub.SkillHubSearchResponse;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Skill Hub read surface for non-agent consumers (CLI, scripts, external agents).
 * Serves the shared skill catalog with scoring search, bare-name lookup and content
 * access; the management CRUD/upload surface stays at {@code /api/skills/*}.
 *
 * @author stephen
 */
public interface SkillHubWebService {
    @GET
    @Path("/api/hub/skills")
    SkillHubSearchResponse search(SkillHubSearchRequest request);

    @GET
    @Path("/api/hub/skills/lookup")
    SkillHubLookupResponse lookup(SkillHubLookupRequest request);

    @GET
    @Path("/api/hub/skills/:namespace/:name")
    SkillHubDetail show(@PathParam("namespace") String namespace, @PathParam("name") String name);

    @GET
    @Path("/api/hub/skills/:namespace/:name/resource")
    SkillHubResourceResponse resource(@PathParam("namespace") String namespace, @PathParam("name") String name,
                                      SkillHubResourceRequest request);
}
