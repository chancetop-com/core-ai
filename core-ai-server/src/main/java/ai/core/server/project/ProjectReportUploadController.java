package ai.core.server.project;

import ai.core.api.server.project.ProjectReportView;
import ai.core.server.file.FileService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.UnauthorizedException;

import java.net.URLConnection;
import java.util.Locale;
import java.util.Map;

/**
 * {@code POST /api/projects/:id/subjects/:subjectId/reports} (multipart, first file part): uploads a report
 * produced outside the platform (CLI, local tooling) straight into a project subject. The file is stored via
 * FileService, shared (so the returned token gives colleagues a link) and attributed to the subject with
 * source=upload. The subject is the only classification input: no LLM involved.
 *
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
public class ProjectReportUploadController implements Controller {
    // CLI multipart parts arrive as application/octet-stream: derive the real type from the extension so the
    // browser preview (HTML report, PDF, markdown) works instead of forcing a download
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("zip", "application/zip"));

    static String resolveContentType(String declared, String fileName) {
        if (declared != null && !declared.isBlank() && !"application/octet-stream".equalsIgnoreCase(declared)) return declared;
        var name = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
        int dot = name.lastIndexOf('.');
        var known = CONTENT_TYPES.get(dot >= 0 ? name.substring(dot + 1) : "");
        if (known != null) return known;
        var guessed = URLConnection.guessContentTypeFromName(fileName);
        return guessed != null ? guessed : "application/octet-stream";
    }

    @Inject
    WebContext webContext;
    @Inject
    FileService fileService;
    @Inject
    ProjectService projectService;
    @Inject
    ProjectAttributionStore attributionStore;
    @Inject
    MongoCollection<ai.core.server.domain.User> userCollection;

    @Override
    public Response execute(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        var projectId = request.pathParam("id");
        var subjectId = request.pathParam("subjectId");
        var project = projectService.require(projectId);
        projectService.requireAccess(project, userId, ProjectAccess.admin(userCollection, userId));
        var subject = projectService.subject(projectId, subjectId);

        var files = request.files();
        if (files.isEmpty()) throw new BadRequestException("no file uploaded");
        var file = files.entrySet().iterator().next().getValue();
        var requestedName = request.queryParams().get("file_name");
        var fileName = requestedName != null && !requestedName.isBlank() ? requestedName : file.fileName;
        var contentType = resolveContentType(file.contentType, fileName);

        var record = fileService.upload(userId, fileName, contentType, file.path);
        var shared = fileService.share(record.id, userId);
        attributionStore.attributeFile(project.id, subject.id, record.id, ProjectAttributionStore.SOURCE_UPLOAD, null, record.createdAt);

        var view = new ProjectReportView();
        view.fileId = record.id;
        view.fileName = record.fileName;
        view.contentType = record.contentType;
        view.size = record.size;
        view.createdAt = record.createdAt;
        view.subjectId = subject.id;
        view.source = ProjectReportQueryService.SOURCE_UPLOAD;
        view.shareToken = shared.shareToken;
        return Response.bean(view);
    }
}
