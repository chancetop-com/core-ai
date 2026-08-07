package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SandboxFileServiceTest {
    private SandboxService service;

    @AfterEach
    void shutdownService() {
        if (service != null) service.shutdown();
    }

    @Test
    void persistsVerifiedReferenceAfterSandboxUploadSucceeds() {
        var fixture = fixture();
        var bytes = "sheet".getBytes(StandardCharsets.UTF_8);
        when(fixture.storage.headObject("sandbox", "uploads/object.xlsx"))
                .thenReturn(new ObjectStorageService.ObjectMetadata(
                        5L, "etag-1", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "now"));
        when(fixture.storage.downloadObject("sandbox", "uploads/object.xlsx")).thenReturn(bytes);

        service.uploadFiles("session-1", "user-1", List.of(new PendingFile(
                "metrics.xlsx", "sandbox", "uploads/object.xlsx",
                "application/octet-stream")));

        verify(fixture.sandbox).uploadFile("/tmp/metrics.xlsx", bytes);
        var captor = ArgumentCaptor.forClass(SessionAttachmentRef.class);
        verify(fixture.repository).insert(captor.capture());
        var reference = captor.getValue();
        assertEquals("session-1", reference.sessionId);
        assertEquals("user-1", reference.userId);
        assertEquals(SessionAttachmentRef.KIND_SANDBOX, reference.kind);
        assertEquals("/tmp/metrics.xlsx", reference.targetPath);
        assertEquals("sandbox", reference.container);
        assertEquals("uploads/object.xlsx", reference.blobName);
        assertEquals("etag-1", reference.sourceETag);
        assertEquals(5L, reference.sourceSizeBytes);
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", reference.contentType);
    }

    @Test
    void failedDownloadDoesNotPersistReference() {
        var fixture = fixture();
        when(fixture.storage.headObject("sandbox", "uploads/missing.xlsx"))
                .thenReturn(new ObjectStorageService.ObjectMetadata(5L, "etag-1", "application/octet-stream", "now"));
        when(fixture.storage.downloadObject("sandbox", "uploads/missing.xlsx"))
                .thenThrow(new IllegalStateException("missing"));

        assertThrows(IllegalStateException.class, () -> service.uploadFiles(
                "session-1", "user-1",
                List.of(new PendingFile("missing.xlsx", "sandbox", "uploads/missing.xlsx", "application/octet-stream"))));

        verify(fixture.repository, never()).insert(any());
    }

    @Test
    void rejectsUnexpectedContainerAndBlobPrefixBeforeStorageRead() {
        var fixture = fixture();

        assertThrows(IllegalArgumentException.class, () -> service.uploadFiles(
                "session-1", "user-1",
                List.of(new PendingFile("secret.xlsx", "other", "uploads/object.xlsx", "application/octet-stream"))));
        assertThrows(IllegalArgumentException.class, () -> service.uploadFiles(
                "session-1", "user-1",
                List.of(new PendingFile("secret.xlsx", "sandbox", "private/object.xlsx", "application/octet-stream"))));

        verify(fixture.storage, never()).headObject(any(), any());
        verify(fixture.repository, never()).insert(any());
        verify(fixture.provider, never()).acquire(any(), any(), any());
    }

    @Test
    void rehydratesNewestReferencePerTargetAndContinuesAfterFailure() {
        var fixture = fixture();
        var newest = reference("new", "report.xlsx", "/tmp/report.xlsx", "uploads/new.xlsx", 1);
        var older = reference("old", "report.xlsx", "/tmp/report.xlsx", "uploads/old.xlsx", 2);
        var missing = reference("missing", "missing.csv", "/tmp/missing.csv", "uploads/missing.csv", 3);
        when(fixture.repository.findSandboxAttachments("session-1", "user-1"))
                .thenReturn(List.of(newest, older, missing));
        var bytes = "new".getBytes(StandardCharsets.UTF_8);
        when(fixture.storage.headObject("sandbox", "uploads/new.xlsx"))
                .thenReturn(new ObjectStorageService.ObjectMetadata(3L, "etag-new", "application/octet-stream", "now"));
        when(fixture.storage.headObject("sandbox", "uploads/missing.csv"))
                .thenReturn(new ObjectStorageService.ObjectMetadata(3L, "etag-missing", "text/csv", "now"));
        when(fixture.storage.downloadObject("sandbox", "uploads/new.xlsx")).thenReturn(bytes);
        when(fixture.storage.downloadObject("sandbox", "uploads/missing.csv"))
                .thenThrow(new IllegalStateException("gone"));

        service.ensureSandboxReady("session-1");

        verify(fixture.sandbox).uploadFile("/tmp/report.xlsx", bytes);
        verify(fixture.storage, never()).downloadObject("sandbox", "uploads/old.xlsx");
        verify(fixture.storage).downloadObject("sandbox", "uploads/missing.csv");
    }

    @Test
    @SuppressFBWarnings("NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION")
    void restoredSnapshotOnlyRehydratesAttachmentsNewerThanSnapshot() {
        var snapshotService = mock(SandboxSnapshotService.class);
        when(snapshotService.enabled()).thenReturn(true);
        var snapshotCreatedAt = ZonedDateTime.now().minusMinutes(2);
        when(snapshotService.restoreLatestWithMetadata("session-1", "user-1", null, 0))
                .thenReturn(new SandboxSnapshotService.RestoreResult(
                        SandboxSnapshotService.RestoreOutcome.RESTORED, snapshotCreatedAt));
        var fixture = fixture(snapshotService);
        var covered = reference("covered", "covered.xlsx", "/tmp/covered.xlsx", "uploads/covered.xlsx", 3);
        var newer = reference("newer", "newer.xlsx", "/tmp/newer.xlsx", "uploads/newer.xlsx", 1);
        when(fixture.repository.findSandboxAttachments("session-1", "user-1"))
                .thenReturn(List.of(newer, covered));
        when(fixture.storage.headObject("sandbox", "uploads/newer.xlsx"))
                .thenReturn(new ObjectStorageService.ObjectMetadata(3L, "etag-newer", "application/octet-stream", "now"));
        when(fixture.storage.downloadObject("sandbox", "uploads/newer.xlsx"))
                .thenReturn("new".getBytes(StandardCharsets.UTF_8));

        service.ensureSandboxReady("session-1");

        verify(fixture.storage, never()).downloadObject("sandbox", "uploads/covered.xlsx");
        verify(fixture.sandbox).uploadFile("/tmp/newer.xlsx", "new".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void changedSourceObjectIsNotRestored() {
        var fixture = fixture();
        var reference = reference("changed", "report.xlsx", "/tmp/report.xlsx", "uploads/report.xlsx", 1);
        when(fixture.repository.findSandboxAttachments("session-1", "user-1")).thenReturn(List.of(reference));
        when(fixture.storage.headObject("sandbox", "uploads/report.xlsx"))
                .thenReturn(new ObjectStorageService.ObjectMetadata(3L, "different-etag", "application/octet-stream", "now"));

        service.ensureSandboxReady("session-1");

        verify(fixture.storage, never()).downloadObject("sandbox", "uploads/report.xlsx");
        verify(fixture.sandbox, never()).uploadFile(eq("/tmp/report.xlsx"), any());
    }

    private Fixture fixture() {
        return fixture(null);
    }

    private Fixture fixture(SandboxSnapshotService snapshotService) {
        var provider = mock(SandboxProvider.class);
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn("sandbox-1");
        when(sandbox.getStatus()).thenReturn(SandboxStatus.READY);
        when(provider.acquire(any(), eq("session-1"), eq("user-1"))).thenReturn(sandbox);
        var storage = mock(ObjectStorageService.class);
        var resolver = mock(ObjectStorageServiceResolver.class);
        when(resolver.resolve()).thenReturn(storage);
        when(resolver.sandboxContainer()).thenReturn("sandbox");
        var repository = mock(SessionAttachmentRefRepository.class);
        when(repository.findSandboxAttachments("session-1", "user-1")).thenReturn(List.of());
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        service = new SandboxService(provider, config, null,
                new SandboxServiceDependencies(null, snapshotService, resolver, null, repository));
        service.createSessionSandbox(config, "session-1", "user-1", null);
        return new Fixture(provider, sandbox, storage, repository);
    }

    private SessionAttachmentRef reference(String id, String fileName, String targetPath, String blobName, int age) {
        var reference = new SessionAttachmentRef();
        reference.id = id;
        reference.sessionId = "session-1";
        reference.userId = "user-1";
        reference.kind = SessionAttachmentRef.KIND_SANDBOX;
        reference.fileName = fileName;
        reference.targetPath = targetPath;
        reference.container = "sandbox";
        reference.blobName = blobName;
        reference.sourceETag = "etag-" + id;
        reference.sourceSizeBytes = 3L;
        reference.createdAt = ZonedDateTime.now().minusMinutes(age);
        return reference;
    }

    private record Fixture(SandboxProvider provider, Sandbox sandbox, ObjectStorageService storage,
                           SessionAttachmentRefRepository repository) {
    }
}
