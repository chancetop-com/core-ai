package ai.core.server.artifact;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.file.FileService;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GetVideoStatusTool;

import java.io.IOException;
import java.nio.file.Files;
import java.time.ZonedDateTime;

/**
 * @author Stephen
 */
public final class ServerImageOutputSink implements GenerateImageTool.ImageOutputSink, GetVideoStatusTool.VideoOutputSink {
    private final String userId;
    private final FileService fileService;
    private final ArtifactSink artifactSink;
    private final PublicUrlConfiguration publicUrlConfiguration;

    public ServerImageOutputSink(String userId, FileService fileService, ArtifactSink artifactSink,
                                 PublicUrlConfiguration publicUrlConfiguration) {
        this.userId = userId;
        this.fileService = fileService;
        this.artifactSink = artifactSink;
        this.publicUrlConfiguration = publicUrlConfiguration;
    }

    @Override
    public String save(String fileName, String contentType, byte[] bytes) {
        try {
            var tempFile = Files.createTempFile("core-ai-image-", "." + extension(fileName));
            Files.write(tempFile, bytes);
            var record = fileService.upload(userId, fileName, contentType, tempFile);
            var artifact = new AgentRunArtifact();
            artifact.fileId = record.id;
            artifact.fileName = record.fileName;
            artifact.contentType = record.contentType;
            artifact.size = record.size;
            artifact.title = contentType.startsWith("video/") ? "Generated video" : "Generated image";
            artifact.createdAt = ZonedDateTime.now();
            artifactSink.append(artifact);
            var shared = fileService.share(record.id, userId);
            return publicUrlConfiguration.sharedArtifactDownloadUrl(shared.shareToken);
        } catch (IOException e) {
            throw new RuntimeException("failed to save generated image", e);
        }
    }

    /**
     * Publishes a file that already lives in the file store (a drama keyframe take) as a run artifact
     * and returns its shared URL — same visibility as a generate_image result, no second upload.
     */
    public String publishExisting(String fileId, String title) {
        var record = fileService.get(fileId);
        var artifact = new AgentRunArtifact();
        artifact.fileId = record.id;
        artifact.fileName = record.fileName;
        artifact.contentType = record.contentType;
        artifact.size = record.size;
        artifact.title = title;
        artifact.createdAt = ZonedDateTime.now();
        artifactSink.append(artifact);
        var shared = fileService.share(record.id, userId);
        return publicUrlConfiguration.sharedArtifactDownloadUrl(shared.shareToken);
    }

    private String extension(String fileName) {
        var index = fileName.lastIndexOf('.');
        return index >= 0 ? fileName.substring(index + 1) : "png";
    }
}
