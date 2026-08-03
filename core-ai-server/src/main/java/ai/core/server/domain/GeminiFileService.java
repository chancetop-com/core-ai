package ai.core.server.domain;

import ai.core.server.blob.ObjectStorageConfiguration;
import ai.core.tool.tools.UnderstandVideoTool;
import core.framework.inject.Inject;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

/**
 * @author stephen
 */
public class GeminiFileService {
    // Vertex generateContent inline payload is capped at 100MB (base64 encoded); 64MB raw keeps the base64 payload safely below it.
    private static final long MAX_INLINE_VIDEO_BYTES = 64L * 1024 * 1024;

    @Inject
    GeminiFileRepository repository;

    @Inject
    SessionAttachmentRefRepository attachmentRepository;

    @Inject
    ObjectStorageConfiguration objectStorageConfiguration;

    private GeminiFilesClient filesClient;

    public void configure(GeminiFilesClient client) {
        this.filesClient = client;
    }

    public ResolvedFile ensureActive(UnderstandVideoTool.AttachmentOwner owner, String referenceId,
                                     String providerId, String upstreamModel) {
        return ensureActive(owner, referenceId, providerId, upstreamModel, filesClient);
    }

    public ResolvedFile ensureActive(UnderstandVideoTool.AttachmentOwner owner, String referenceId,
                                     String providerId, String upstreamModel, GeminiFilesClient client) {
        var reference = attachmentRepository.findOwned(referenceId, owner.sessionId(), owner.userId());
        if (reference == null) throw new IllegalArgumentException("video attachment not found");
        if (reference.sourceETag == null) throw new IllegalArgumentException("video attachment has no source version");

        var cached = repository.findBySource(owner.userId(), providerId, upstreamModel,
                reference.container, reference.blobName, reference.sourceETag);
        if (cached != null && cached.state == GeminiFile.GeminiFileState.ACTIVE
                && cached.expiresAt != null && cached.expiresAt.isAfter(ZonedDateTime.now())) {
            return new ResolvedFile(cached.geminiFileName, cached.geminiFileUri, true, cached.contentType);
        }
        if (client == null) throw new IllegalStateException("Gemini Files client is not configured");
        return uploadAndActivate(owner, providerId, upstreamModel, reference, client);
    }

    /**
     * Downloads the video attachment and returns it as a base64 inline payload for Vertex generateContent.
     */
    public InlineVideo loadInlineVideo(UnderstandVideoTool.AttachmentOwner owner, String referenceId) {
        var reference = attachmentRepository.findOwned(referenceId, owner.sessionId(), owner.userId());
        if (reference == null) throw new IllegalArgumentException("video attachment not found");
        if (reference.sourceETag == null) throw new IllegalArgumentException("video attachment has no source version");
        if (reference.sourceSizeBytes != null && reference.sourceSizeBytes > MAX_INLINE_VIDEO_BYTES) {
            throw new IllegalArgumentException("video is too large for inline video understanding: " + reference.sourceSizeBytes + " bytes; configure a GCS bucket for larger videos");
        }
        if (objectStorageConfiguration == null || objectStorageConfiguration.service == null) {
            throw new IllegalStateException("object storage is not configured");
        }
        var id = "gemini_" + UUID.randomUUID();
        var temp = Path.of(System.getProperty("java.io.tmpdir"), id + ".video");
        try {
            objectStorageConfiguration.service.downloadObjectToFile(reference.container, reference.blobName, temp);
            var bytes = Files.readAllBytes(temp);
            if (bytes.length > MAX_INLINE_VIDEO_BYTES) {
                throw new IllegalArgumentException("video is too large for inline video understanding: " + bytes.length + " bytes; configure a GCS bucket for larger videos");
            }
            return new InlineVideo(Base64.getEncoder().encodeToString(bytes), reference.contentType);
        } catch (IOException e) {
            throw new RuntimeException("failed to load video for inline understanding", e);
        } finally {
            deleteTempFile(temp);
        }
    }

    private ResolvedFile uploadAndActivate(UnderstandVideoTool.AttachmentOwner owner, String providerId,
                                           String upstreamModel, SessionAttachmentRef reference, GeminiFilesClient client) {
        var id = "gemini_" + UUID.randomUUID();
        var temp = Path.of(System.getProperty("java.io.tmpdir"), id + ".video");
        try {
            if (objectStorageConfiguration == null || objectStorageConfiguration.service == null) {
                throw new IllegalStateException("object storage is not configured");
            }
            objectStorageConfiguration.service.downloadObjectToFile(reference.container, reference.blobName, temp);
            var uploaded = client.upload(temp, reference.contentType, reference.fileName);
            var state = uploaded.state();
            var deadline = System.nanoTime() + java.time.Duration.ofMinutes(10).toNanos();
            while ("PROCESSING".equalsIgnoreCase(state) && System.nanoTime() < deadline) {
                Thread.sleep(1000L);
                state = client.get(uploaded.name()).state();
            }
            if (!"ACTIVE".equalsIgnoreCase(state)) throw new IllegalStateException("Gemini file did not become active");
            var file = new GeminiFile();
            file.id = id;
            file.userId = owner.userId();
            file.providerId = providerId;
            file.upstreamModel = upstreamModel;
            file.container = reference.container;
            file.blobName = reference.blobName;
            file.contentType = reference.contentType;
            file.fileName = reference.fileName;
            file.sourceSizeBytes = reference.sourceSizeBytes;
            file.sourceETag = reference.sourceETag;
            file.geminiFileName = uploaded.name();
            file.geminiFileUri = uploaded.uri();
            file.state = GeminiFile.GeminiFileState.ACTIVE;
            file.expiresAt = parseExpiration(uploaded.expirationTime());
            if (file.expiresAt == null) file.expiresAt = ZonedDateTime.now().plusDays(7);
            file.createdAt = ZonedDateTime.now();
            file.updatedAt = file.createdAt;
            repository.insert(file);
            return new ResolvedFile(file.geminiFileName, file.geminiFileUri, false, file.contentType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gemini file processing interrupted", e);
        } finally {
            deleteTempFile(temp);
        }
    }

    private void deleteTempFile(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete temporary video file", e);
        }
    }

    private ZonedDateTime parseExpiration(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ZonedDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("invalid Gemini expiration time", e);
        }
    }

    public record ResolvedFile(String name, String uri, boolean cacheHit, String contentType) { }

    public record InlineVideo(String base64Data, String contentType) { }
}
