package ai.core.api.server.foryou;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface ForYouWebService {
    @GET
    @Path("/api/for-you")
    ForYouDashboardView dashboard();

    @GET
    @Path("/api/for-you/reports")
    ListForYouReportsResponse listReports();

    @POST
    @Path("/api/for-you/reports")
    ForYouReportView createReport(ForYouReportRequest request);

    @PUT
    @Path("/api/for-you/reports/:id")
    ForYouReportView updateReport(@PathParam("id") String id, ForYouReportRequest request);

    @DELETE
    @Path("/api/for-you/reports/:id")
    DeleteForYouItemResponse deleteReport(@PathParam("id") String id);

    @GET
    @Path("/api/for-you/todos")
    ListForYouTodosResponse listTodos();

    @POST
    @Path("/api/for-you/todos")
    ForYouTodoView createTodo(ForYouTodoRequest request);

    @PUT
    @Path("/api/for-you/todos/:id")
    ForYouTodoView updateTodo(@PathParam("id") String id, ForYouTodoRequest request);

    @DELETE
    @Path("/api/for-you/todos/:id")
    DeleteForYouItemResponse deleteTodo(@PathParam("id") String id);

    @GET
    @Path("/api/for-you/files")
    ListForYouFilesResponse listFiles();

    @GET
    @Path("/api/for-you/token-usage")
    ForYouTokenUsageView tokenUsage(ForYouTokenUsageRequest request);
}
