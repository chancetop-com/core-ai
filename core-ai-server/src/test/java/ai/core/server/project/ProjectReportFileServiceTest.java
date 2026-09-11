package ai.core.server.project;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.file.FileService;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Report-directory file operations: rename/delete and the rule deciding which file of the directory may be touched
 * (a report filed under this project, or the caller's own unfiled artifact).
 *
 * @author core-ai
 */
class ProjectReportFileServiceTest {
    private static FileRecord record(String id, String userId) {
        var record = new FileRecord();
        record.id = id;
        record.userId = userId;
        return record;
    }

    private ProjectReportFileService service;
    private ProjectService projectService;
    private FileService fileService;
    private ProjectAttributionStore attributionStore;
    private MongoCollection<ProjectSubject> subjects;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ProjectReportFileService();
        projectService = mock(ProjectService.class);
        service.projectService = projectService;
        fileService = mock(FileService.class);
        service.fileService = fileService;
        attributionStore = mock(ProjectAttributionStore.class);
        service.attributionStore = attributionStore;
        subjects = (MongoCollection<ProjectSubject>) mock(MongoCollection.class);
        service.subjectCollection = subjects;

        var project = new Project();
        project.id = "p-1";
        project.userId = "user-1";
        when(projectService.require("p-1")).thenReturn(project);
    }

    @Test
    void renameReportRenamesTheFileRecord() {
        when(fileService.get("f-1")).thenReturn(record("f-1", "user-1"));

        service.renameReport("p-1", "user-1", false, "f-1", "weekly report.html");

        verify(fileService).rename("f-1", "weekly report.html");
    }

    @Test
    void deleteReportDeletesFileAndDropsSubjectReportPointers() {
        when(fileService.get("f-1")).thenReturn(record("f-1", "user-1"));
        var subject = new ProjectSubject();
        subject.id = "s-1";
        subject.reportFileId = "f-1";
        when(projectService.subjects("p-1")).thenReturn(List.of(subject));

        service.deleteReport("p-1", "user-1", false, "f-1");

        verify(fileService).delete("f-1");
        // the subject's rendered report was that very file: the pointers must not survive as a dangling iframe
        verify(subjects).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void reportWriteAllowsFiledReportOfAnotherUser() {
        when(attributionStore.fileAttribution("p-1", "f-3")).thenReturn(Optional.of(new ProjectSubjectAttribution()));

        service.renameReport("p-1", "user-1", false, "f-3", "member report");

        verify(fileService).rename("f-3", "member report");
    }

    @Test
    void reportWriteRejectsUnfiledFileOfAnotherUser() {
        when(fileService.get("f-2")).thenReturn(record("f-2", "user-2"));

        assertThrows(ForbiddenException.class, () -> service.renameReport("p-1", "user-1", false, "f-2", "stolen"));
        assertThrows(ForbiddenException.class, () -> service.deleteReport("p-1", "user-1", false, "f-2"));
        verify(fileService, never()).delete("f-2");
    }

    @Test
    void renameReportRequiresAFileId() {
        assertThrows(BadRequestException.class, () -> service.renameReport("p-1", "user-1", false, " ", "name"));
    }
}
