package ai.core.server.artifact;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.FileRecord;
import ai.core.server.file.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerImageOutputSinkTest {
    private FileService fileService;
    private ArtifactSink artifactSink;
    private ServerImageOutputSink sink;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        artifactSink = mock(ArtifactSink.class);
        sink = new ServerImageOutputSink("user-1", fileService, artifactSink, new PublicUrlConfiguration("https://files.example.com"));
    }

    @Test
    void saveReusesRecordOfIdenticalContentInsteadOfUploadingAgain() {
        var stored = existingRecord("file-1", "generated-image.png");
        when(fileService.uploadIfAbsent(eq("user-1"), eq("image-1.png"), eq("image/png"), any())).thenReturn(stored);
        when(fileService.share("file-1", "user-1")).thenReturn(stored);

        var url = sink.save("image-1.png", "image/png", "png-bytes".getBytes(StandardCharsets.UTF_8));

        assertEquals("https://files.example.com/api/public/artifacts/token-1/content", url);
        var artifact = ArgumentCaptor.forClass(AgentRunArtifact.class);
        verify(artifactSink).append(artifact.capture());
        assertEquals("file-1", artifact.getValue().fileId);
        assertEquals("generated-image.png", artifact.getValue().fileName);
        assertEquals("image/png", artifact.getValue().contentType);
        assertEquals("Generated image", artifact.getValue().title);
    }

    private FileRecord existingRecord(String id, String fileName) {
        var record = new FileRecord();
        record.id = id;
        record.fileName = fileName;
        record.contentType = "image/png";
        record.size = 9L;
        record.shareToken = "token-1";
        return record;
    }
}
