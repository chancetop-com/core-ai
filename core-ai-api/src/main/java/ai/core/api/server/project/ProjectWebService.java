package ai.core.api.server.project;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * Project (campaign container) aggregation APIs: cockpit (content layer) + detail tabs (aggregation layer).
 *
 * @author stephen
 */
public interface ProjectWebService {
    @GET
    @Path("/api/projects")
    ListProjectsResponse list(ListProjectsRequest request);

    @POST
    @Path("/api/projects")
    @ResponseStatus(HTTPStatus.CREATED)
    CreateProjectResponse create(CreateProjectRequest request);

    @GET
    @Path("/api/projects/:id")
    ProjectView get(@PathParam("id") String id, GetProjectRequest request);

    @PUT
    @Path("/api/projects/:id")
    void update(@PathParam("id") String id, UpdateProjectRequest request);

    @POST
    @Path("/api/projects/:id/archive")
    void archive(@PathParam("id") String id);

    @POST
    @Path("/api/projects/:id/activate")
    void activate(@PathParam("id") String id);

    @GET
    @Path("/api/projects/:id/executions")
    ListProjectExecutionsResponse executions(@PathParam("id") String id, ListProjectExecutionsRequest request);

    @GET
    @Path("/api/projects/:id/reports")
    ListProjectReportsResponse reports(@PathParam("id") String id, ListProjectReportsRequest request);

    @POST
    @Path("/api/projects/:id/analyze")
    AnalyzeProjectResponse analyze(@PathParam("id") String id, AnalyzeProjectRequest request);

    @POST
    @Path("/api/projects/:id/subjects/:subjectId/report")
    void report(@PathParam("id") String id, @PathParam("subjectId") String subjectId);

    @POST
    @Path("/api/projects/:id/subjects/:subjectId/reset-analysis")
    void resetSubjectAnalysis(@PathParam("id") String id, @PathParam("subjectId") String subjectId);

    @GET
    @Path("/api/projects/:id/events")
    ListProjectEventsResponse events(@PathParam("id") String id, ListProjectEventsRequest request);

    @POST
    @Path("/api/projects/builtin-agents/reset")
    void resetBuiltinAgents();

    @GET
    @Path("/api/projects/:id/subjects")
    ListProjectSubjectsResponse subjects(@PathParam("id") String id, ListProjectSubjectsRequest request);

    @POST
    @Path("/api/projects/:id/subjects")
    @ResponseStatus(HTTPStatus.CREATED)
    CreateSubjectResponse createSubject(@PathParam("id") String id, CreateSubjectRequest request);

    @PUT
    @Path("/api/projects/:id/subjects/:subjectId")
    void updateSubject(@PathParam("id") String id, @PathParam("subjectId") String subjectId, UpdateSubjectRequest request);

    @DELETE
    @Path("/api/projects/:id/subjects/:subjectId")
    void deleteSubject(@PathParam("id") String id, @PathParam("subjectId") String subjectId);

    @GET
    @Path("/api/projects/:id/stats")
    ProjectStatsView stats(@PathParam("id") String id, GetProjectStatsRequest request);

    @GET
    @Path("/api/projects/:id/members")
    ListProjectMembersResponse members(@PathParam("id") String id);

    @GET
    @Path("/api/projects/:id/member-options")
    ListProjectMembersResponse memberOptions(@PathParam("id") String id);

    @POST
    @Path("/api/projects/:id/members")
    @ResponseStatus(HTTPStatus.CREATED)
    void addMember(@PathParam("id") String id, AddProjectMemberRequest request);

    @DELETE
    @Path("/api/projects/:id/members/:type/:memberId")
    void removeMember(@PathParam("id") String id, @PathParam("type") String type, @PathParam("memberId") String memberId);

    @GET
    @Path("/api/projects/:id/timeline")
    ListTimelineResponse timeline(@PathParam("id") String id, ListTimelineRequest request);
}
