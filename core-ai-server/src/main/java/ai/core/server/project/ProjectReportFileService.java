package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.file.FileService;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;

import java.util.Objects;

/**
 * Report-directory file operations of a project: renaming a report (display metadata only, the stored content is
 * untouched) and deleting one (the file record itself, its stored content and the attribution rows of every project).
 * Both are project.manage actions, but not every file of the directory may be touched by every manager — see
 * {@link #requireReport}.
 *
 * @author stephen
 */
public class ProjectReportFileService {
    @Inject
    ProjectService projectService;
    @Inject
    FileService fileService;
    @Inject
    ProjectAttributionStore attributionStore;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;

    /** renames a report of the directory (display metadata of the file record — the list shows this name) */
    public void renameReport(String projectId, String userId, boolean admin, String fileId, String fileName) {
        var project = projectService.require(projectId);
        projectService.requireAccess(project, userId, admin);
        requireReport(project, userId, admin, fileId);
        fileService.rename(fileId, fileName);
    }

    /**
     * Deletes a report of the directory: the file record and its stored content are removed and every project drops
     * its attribution rows (see FileService.delete). A rendered subject report pointing at the file loses its
     * pointers too, so the report tab does not keep an iframe over a deleted file.
     */
    public void deleteReport(String projectId, String userId, boolean admin, String fileId) {
        var project = projectService.require(projectId);
        projectService.requireAccess(project, userId, admin);
        requireReport(project, userId, admin, fileId);
        fileService.delete(fileId);
        clearSubjectReport(project.id, fileId);
    }

    // the directory lists two kinds of rows: files filed under a subject of this project, and the unfiled artifacts
    // of member sessions/runs (the inbox). A filed row proves membership; an unfiled file must be the caller's own —
    // without that check the endpoint would let a project manager rename/delete any file of the system by id.
    private void requireReport(Project project, String userId, boolean admin, String fileId) {
        if (fileId == null || fileId.isBlank()) throw new BadRequestException("file_id is required");
        if (admin) return;
        if (attributionStore.fileAttribution(project.id, fileId).isPresent()) return;
        if (Objects.equals(fileService.get(fileId).userId, userId)) return;
        throw new ForbiddenException("report does not belong to this project, fileId=" + fileId);
    }

    private void clearSubjectReport(String projectId, String fileId) {
        for (var subject : projectService.subjects(projectId)) {
            if (!fileId.equals(subject.reportFileId)) continue;
            subjectCollection.update(Filters.eq("_id", subject.id), Updates.combine(
                Updates.unset("report_file_id"),
                Updates.unset("report_share_token"),
                Updates.unset("report_generated_at")));
        }
    }
}
