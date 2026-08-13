package ai.core.api.server.task;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface TaskWebService {
    @GET
    @Path("/api/admin/tasks")
    ListTasksResponse list(ListTasksRequest request);

    @PUT
    @Path("/api/admin/tasks/:taskId/retry")
    RetryTaskResponse retry(@PathParam("taskId") String taskId);

    @POST
    @Path("/api/admin/tasks")
    RunTaskResponse run(RunTaskRequest request);
}
