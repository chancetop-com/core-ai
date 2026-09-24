package ai.core.server.run;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxFile;
import ai.core.server.artifact.ArtifactSink;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.domain.FileRecord;
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
    private Sandbox sandbox;
    private SubmitArtifactsTool tool;
    private FileRecord record;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        fileService = mock(FileService.class);
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
                new PublicUrlConfiguration("https://core.example.com"));
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

    private ExecutionContext context() {
        return ExecutionContext.builder().userId("user-1").sandbox(sandbox).build();
    }
}
