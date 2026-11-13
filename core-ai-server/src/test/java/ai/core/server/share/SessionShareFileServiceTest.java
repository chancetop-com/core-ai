package ai.core.server.share;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.domain.SessionShare;
import ai.core.server.domain.SessionShareFile;
import ai.core.server.file.FileService;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class SessionShareFileServiceTest {
    private SessionShareFileService service;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        service = new SessionShareFileService();
        service.fileService = mock(FileService.class);
        service.attachmentRepository = mock(SessionAttachmentRefRepository.class);
        service.storageResolver = mock(ObjectStorageServiceResolver.class);
        service.publicUrl = new PublicUrlConfiguration("https://app.test");
        session = new ChatSession();
        session.id = "session";
        session.userId = "owner";
    }

    @Test
    void capturesOnlyOwnedReferencesAndRewritesKnownAliases() {
        var record = new FileRecord();
        record.id = "file-1";
        record.userId = "owner";
        record.fileName = "answer.html";
        record.contentType = "text/html";
        record.size = 20L;
        record.shareToken = "old-file-share";
        when(service.fileService.getOwned("file-1", "owner")).thenReturn(record);
        var artifact = new AgentRunArtifact();
        artifact.fileId = "file-1";
        session.artifacts = List.of(artifact);
        var share = share(service.capture(session, List.of()));
        var download = SessionShareFileService.downloadUrl(share, share.files.getFirst().id);
        assertEquals(download, service.rewrite("/api/files/file-1/content", share));
        assertEquals(download, service.rewrite("https://app.test/shared/artifacts/old-file-share", share));
        assertEquals("![image](" + download + ")", service.rewrite("![image](https://app.test/api/files/file-1/content)", share));
        assertEquals("'" + download + "'", service.rewrite("'https://app.test/api/files/file-1/content'", share));
        assertEquals("[attachment unavailable]", service.rewrite("/api/files/other-file/content", share));
        assertEquals("https://example.org/docs", service.rewrite("https://example.org/docs", share));
        for (var prefix : List.of("https://", "HTTPS://", "//", "ftp://")) {
            var external = prefix + "unrelated.example/api/files/file-1/content";
            assertEquals(external, service.rewrite(external, share));
        }
        for (var external : List.of("https://[2001:db8::1]/api/files/file-1/content",
            "https://unrelated.example/a)b/api/files/file-1/content", "https://unrelated.example/a'/api/files/file-1/content")) {
            assertEquals(external, service.rewrite(external, share));
        }
    }

    @Test
    void matchesImageUrlToOwnedSourceAndNeverDownloadsDuringCapture() {
        var url = "https://storage.test/uploads/picture.png";
        when(service.storageResolver.webAssetsPublicBaseUrl()).thenReturn("https://storage.test");
        when(service.storageResolver.locate(url)).thenReturn(new ObjectStorageServiceResolver.BlobLocation("uploads", "picture.png"));
        var reference = new SessionAttachmentRef();
        reference.id = "image-1";
        reference.sessionId = session.id;
        reference.userId = session.userId;
        reference.container = "uploads";
        reference.blobName = "picture.png";
        when(service.attachmentRepository.findBySource(session.id, session.userId, "uploads", "picture.png")).thenReturn(reference);
        var message = new ChatMessage();
        message.sessionId = session.id;
        message.content = url;
        var files = service.capture(session, List.of(message));
        assertEquals(1, files.size());
        assertEquals("image-1", files.getFirst().attachmentRefId);
        verify(service.storageResolver, never()).resolve();
    }

    @Test
    void refusesArbitraryRefIdsAndOversizedDownloads() {
        var file = new SessionShareFile();
        file.id = "allowed";
        file.size = SessionShareFileService.MAX_DOWNLOAD_BYTES + 1;
        var share = share(List.of(file));
        assertThrows(NotFoundException.class, () -> service.requireFile(share, "another"));
        assertThrows(BadRequestException.class, () -> service.read(share, file));
        verify(service.fileService, never()).getOwned(anyString(), anyString());
    }

    @Test
    void rejectsAttachmentWhoseOwnershipOrObjectVersionChanged() {
        var file = new SessionShareFile();
        file.id = "allowed";
        file.attachmentRefId = "ref";
        file.container = "uploads";
        file.blobName = "picture.png";
        file.size = 10L;
        var share = share(List.of(file));
        assertThrows(NotFoundException.class, () -> service.read(share, file));
        var reference = new SessionAttachmentRef();
        reference.container = file.container;
        reference.blobName = file.blobName;
        reference.sourceETag = "original";
        when(service.attachmentRepository.findOwned("ref", "session", "owner")).thenReturn(reference);
        var storage = mock(ObjectStorageService.class);
        when(service.storageResolver.resolve()).thenReturn(storage);
        when(storage.headObject("uploads", "picture.png")).thenReturn(new ObjectStorageService.ObjectMetadata(10L, "new", "image/png", null));
        assertThrows(NotFoundException.class, () -> service.read(share, file));
        verify(storage, never()).downloadObject(anyString(), anyString());
    }

    @Test
    void doesNotAddNewArtifactsToExistingManifest() {
        var share = share(List.of());
        assertTrue(service.rewrite("/api/files/new-file/content", share).contains("unavailable"));
        assertThrows(NotFoundException.class, () -> service.requireFile(share, "new-file"));
    }

    @Test
    void doesNotCaptureAFileFromAnExternalOriginWithASimilarPath() {
        var message = new ChatMessage();
        message.sessionId = session.id;
        for (var prefix : List.of("https://", "HTTPS://", "//", "ftp://")) {
            message.content = prefix + "unrelated.example/api/files/file-1/content";
            assertTrue(service.capture(session, List.of(message)).isEmpty());
        }
        for (var external : List.of("https://[2001:db8::1]/api/files/file-1/content",
            "https://unrelated.example/a)b/api/files/file-1/content", "https://unrelated.example/a'/api/files/file-1/content")) {
            message.content = external;
            assertTrue(service.capture(session, List.of(message)).isEmpty());
        }
        verify(service.fileService, never()).getOwned(anyString(), anyString());
    }

    @Test
    void doesNotCaptureAnAttachmentFromAnUnrelatedStorageAccount() {
        var message = new ChatMessage();
        message.sessionId = session.id;
        message.content = "https://other.blob.core.windows.net/uploads/picture.png";
        when(service.storageResolver.locate(message.content)).thenReturn(new ObjectStorageServiceResolver.BlobLocation("uploads", "picture.png"));
        assertTrue(service.capture(session, List.of(message)).isEmpty());
        verify(service.attachmentRepository, never()).findBySource(anyString(), anyString(), anyString(), anyString());
    }

    private SessionShare share(List<SessionShareFile> files) {
        var share = new SessionShare();
        share.sessionId = "session";
        share.ownerUserId = "owner";
        share.token = "share-token";
        share.files = files;
        return share;
    }
}
