package ai.core.server.run;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxFile;
import ai.core.server.artifact.ArtifactSink;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.file.ArtifactPublisher;
import ai.core.server.file.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmitArtifactsToolTest {
    private FileService fileService;
    private ArtifactPublisher publisher;
    private SessionAttachmentRefRepository attachmentRepository;
    private Sandbox sandbox;
    private SubmitArtifactsTool tool;
    private FileRecord record;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        fileService = mock(FileService.class);
        publisher = mock(ArtifactPublisher.class);
        when(fileService.publisher()).thenReturn(publisher);
        attachmentRepository = mock(SessionAttachmentRefRepository.class);
        sandbox = mock(Sandbox.class);
        record = new FileRecord();
        record.id = "file-1";
        record.userId = "user-1";
        record.fileName = "page.html";
        record.contentType = "text/html";
        record.size = 12L;
        var sandboxPath = Files.createFile(tempDir.resolve("page.html"));
        when(sandbox.downloadFile("/tmp/page.html"))
                .thenReturn(new SandboxFile(sandboxPath, "page.html", "text/html", 12L));
        tool = SubmitArtifactsTool.create("user-1", fileService, mock(ArtifactSink.class),
                new PublicUrlConfiguration("https://core.example.com"), attachmentRepository);
    }

    @Test
    void publicArtifactIsStoredPubliclyAndDownloadUrlPointsAtObjectStorage() {
        when(fileService.uploadIfAbsent(eq("user-1"), eq("page.html"), eq("text/html"), any(Path.class), eq(true)))
                .thenReturn(record);
        record.shareToken = "token-1";
        when(fileService.share("file-1", "user-1")).thenReturn(record);
        when(fileService.publicUrl(record)).thenReturn("https://blob.example.com/public-artifacts/artifacts/file-1.html");

        var result = tool.execute("{\"artifacts\":[{\"path\":\"/tmp/page.html\",\"public\":true}]}", context());

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("https://blob.example.com/public-artifacts/artifacts/file-1.html"));
        verify(fileService).uploadIfAbsent(eq("user-1"), eq("page.html"), eq("text/html"), any(Path.class), eq(true));
    }

    @Test
    void artifactWithoutTheFlagKeepsTheShareLink() {
        when(fileService.uploadIfAbsent(eq("user-1"), eq("page.html"), eq("text/html"), any(Path.class), eq(false)))
                .thenReturn(record);
        record.shareToken = "token-1";
        when(fileService.share("file-1", "user-1")).thenReturn(record);

        var result = tool.execute("{\"artifacts\":[{\"path\":\"/tmp/page.html\"}]}", context());

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("https://core.example.com/api/public/artifacts/token-1/content"));
    }

    @Test
    void publicRequestFallsBackToTheShareLinkWhenNoPublicUrlIsAvailable() {
        when(fileService.uploadIfAbsent(eq("user-1"), eq("page.html"), eq("text/html"), any(Path.class), eq(true)))
                .thenReturn(record);
        record.shareToken = "token-1";
        when(fileService.share("file-1", "user-1")).thenReturn(record);

        var result = tool.execute("{\"artifacts\":[{\"path\":\"/tmp/page.html\",\"public\":true}]}", context());

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("https://core.example.com/api/public/artifacts/token-1/content"));
    }

    @Test
    void fileIdPublishesAnExistingPlatformFile() {
        var published = publicRecord("file-2", "video-abc-1.mp4", "video/mp4");
        when(fileService.getOwned("file-2", "user-1")).thenReturn(recordFor("file-2", "video-abc-1.mp4", "video/mp4"));
        when(publisher.publish(any(FileRecord.class), eq(true))).thenReturn(published);
        when(fileService.publicUrl(published)).thenReturn("https://blob.example.com/public-artifacts/artifacts/file-2.mp4");

        var result = tool.execute("{\"artifacts\":[{\"file_id\":\"file-2\",\"public\":true}]}", context());

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("https://blob.example.com/public-artifacts/artifacts/file-2.mp4"));
        verify(publisher).publish(any(FileRecord.class), eq(true));
    }

    @Test
    void videoReferenceIsCopiedFromPlatformStorage() {
        var reference = new SessionAttachmentRef();
        reference.id = "video_1a2b3c4d-0000-0000-0000-000000000000";
        reference.container = "static";
        reference.blobName = "ai/uploads/abc.mp4";
        reference.fileName = "promo.mp4";
        reference.contentType = "video/mp4";
        when(attachmentRepository.findOwned(reference.id, "session-1", "user-1")).thenReturn(reference);

        var published = publicRecord("file-3", "promo.mp4", "video/mp4");
        when(publisher.copyToArtifact("user-1", "promo.mp4", "video/mp4", "static", "ai/uploads/abc.mp4", true))
                .thenReturn(published);
        when(fileService.publicUrl(published)).thenReturn("https://blob.example.com/public-artifacts/artifacts/file-3.mp4");

        var result = tool.execute("{\"artifacts\":[{\"source\":\"" + reference.id + "\",\"public\":true}]}", context());

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("https://blob.example.com/public-artifacts/artifacts/file-3.mp4"));
        verify(publisher).copyToArtifact("user-1", "promo.mp4", "video/mp4", "static", "ai/uploads/abc.mp4", true);
    }

    @Test
    void itemRequiresExactlyOneSourceSelector() {
        var result = tool.execute("{\"artifacts\":[{}]}", context());

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("exactly one of path, file_id or source"));
    }

    private FileRecord recordFor(String id, String fileName, String contentType) {
        var value = new FileRecord();
        value.id = id;
        value.userId = "user-1";
        value.fileName = fileName;
        value.contentType = contentType;
        value.size = 1024L;
        value.storagePath = "artifacts/artifacts/" + id;
        return value;
    }

    private FileRecord publicRecord(String id, String fileName, String contentType) {
        var value = recordFor(id, fileName, contentType);
        value.storagePath = "public-artifacts/artifacts/" + id;
        return value;
    }

    private ExecutionContext context() {
        return ExecutionContext.builder().sessionId("session-1").userId("user-1").sandbox(sandbox).build();
    }
}
