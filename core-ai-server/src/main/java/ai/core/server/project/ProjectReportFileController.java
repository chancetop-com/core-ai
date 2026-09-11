package ai.core.server.project;

import ai.core.server.domain.User;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.http.HTTPMethod;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.UnauthorizedException;

/**
 * Report-directory file operations, on one resource path:
 * <ul>
 *   <li>{@code PUT /api/projects/:id/reports/:fileId?file_name=<name>} — renames the report (the new name travels as
 *       a query parameter, the same shape the upload controller uses for the same field).</li>
 *   <li>{@code DELETE /api/projects/:id/reports/:fileId} — deletes the report.</li>
 * </ul>
 * Raw controller rather than a JSON api method because the rename takes its only field from the query string.
 *
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
public class ProjectReportFileController implements Controller {
    @Inject
    WebContext webContext;
    @Inject
    ProjectReportFileService reportFileService;
    @Inject
    MongoCollection<User> userCollection;

    @Override
    public Response execute(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        var projectId = request.pathParam("id");
        var fileId = request.pathParam("fileId");
        var admin = ProjectAccess.admin(userCollection, userId);
        if (request.method() == HTTPMethod.DELETE) {
            reportFileService.deleteReport(projectId, userId, admin, fileId);
        } else {
            reportFileService.renameReport(projectId, userId, admin, fileId, request.queryParams().get("file_name"));
        }
        return Response.empty();
    }
}
