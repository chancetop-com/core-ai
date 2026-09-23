package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.domain.SessionAttachmentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stages one message's chat attachments. Each attachment is recorded as a reference, so a sandbox that starts
 * or restarts re-materializes the file, and is written straight into a sandbox that is already running. Files
 * land in the session's working area — {@code /workspace/attachments/<name>}, or {@code /tmp/attachments/}
 * when the provider mounts the workspace read-only — never in the scratch root the agent's own files use.
 *
 * @author stephen
 */
final class AttachmentStaging {
    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentStaging.class);
    private static final String SANDBOX_BLOB_PREFIX = "uploads/";
    private static final String MULTIMODAL_BLOB_PREFIX = "ai/uploads/";

    /** Only this platform's own storage is staged: the sandbox container or the multimodal one. */
    static boolean validSource(SandboxService sandboxService, String container, String blobName) {
        if (container == null || blobName == null) return false;
        var resolver = sandboxService.storageResolver;
        if (resolver == null) return false;
        if (container.equals(resolver.sandboxContainer())) return blobName.startsWith(SANDBOX_BLOB_PREFIX);
        return container.equals(resolver.multimodalContainer()) && blobName.startsWith(MULTIMODAL_BLOB_PREFIX);
    }

    static String effectiveContentType(String storageContentType, String requestContentType) {
        if (storageContentType != null && !storageContentType.isBlank()) return storageContentType;
        if (requestContentType != null && !requestContentType.isBlank()) return requestContentType;
        return "application/octet-stream";
    }

    private final SandboxService sandboxService;
    private final String sessionId;
    private final String userId;
    private final ObjectStorageService storage;
    private final List<SessionAttachmentRef> references;
    private final Set<String> usedTargets;
    /** the running sandbox of this session, or null while it has not been acquired yet */
    private final Sandbox sandbox;

    AttachmentStaging(SandboxService sandboxService, String sessionId, String userId, ObjectStorageService storage,
                      List<SessionAttachmentRef> references) {
        this.sandboxService = sandboxService;
        this.sessionId = sessionId;
        this.userId = userId;
        this.storage = storage;
        this.references = references;
        this.usedTargets = references.stream().map(reference -> reference.targetPath)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        var attached = sandboxService.sessionSandbox(sessionId);
        this.sandbox = attached != null && attached.getStatus() == SandboxStatus.READY ? attached : null;
    }

    /** @return the sandbox path the attachment landed at, or null when it was skipped */
    String stage(PendingFile file) {
        try {
            var fileName = fileName(file);
            if (fileName == null || !validSource(sandboxService, file.container(), file.blobName())) {
                LOGGER.warn("attachment cannot be staged: session={}, container={}, blob={}",
                        sessionId, file.container(), file.blobName());
                return null;
            }
            var existing = existingReference(file.blobName());
            if (existing != null) return existing.targetPath;
            var metadata = storage.headObject(file.container(), file.blobName());
            var size = metadata.sizeBytes();
            if (size != null && size > sandboxService.attachmentMaxBytes) {
                LOGGER.info("attachment over the staging limit, skipped: session={}, file={}, size={}, limit={}",
                        sessionId, fileName, size, sandboxService.attachmentMaxBytes);
                return null;
            }
            var unique = uniqueFileName(fileName);
            var targetPath = upload(unique, file);
            persist(file, metadata, unique, targetPath, size);
            return targetPath;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to stage attachment into sandbox: session={}, file={}", sessionId, file.fileName(), e);
            return null;
        }
    }

    /** Uploads when the sandbox is live; otherwise the recorded reference materializes on the next acquire. */
    private String upload(String fileName, PendingFile file) {
        var root = SandboxAttachmentPath.root(sandboxService.workspaceWritable());
        if (sandbox == null) return SandboxAttachmentPath.targetPath(root, fileName);
        var data = storage.downloadObject(file.container(), file.blobName());
        try {
            var targetPath = SandboxAttachmentPath.targetPath(root, fileName);
            sandbox.uploadFile(targetPath, data);
            return targetPath;
        } catch (RuntimeException e) {
            var fallback = SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, fileName);
            LOGGER.warn("staging into {} failed, falling back to {}: session={}, error={}",
                    root, fallback, sessionId, e.getMessage());
            sandbox.uploadFile(fallback, data);
            return fallback;
        }
    }

    private void persist(PendingFile file, ObjectStorageService.ObjectMetadata metadata, String fileName,
                         String targetPath, Long size) {
        var reference = new SessionAttachmentRef();
        reference.id = "sandbox_" + UUID.randomUUID();
        reference.sessionId = sessionId;
        reference.userId = userId;
        reference.kind = SessionAttachmentRef.KIND_SANDBOX;
        reference.container = file.container();
        reference.blobName = file.blobName();
        reference.sourceETag = metadata.etag();
        reference.sourceSizeBytes = size;
        reference.contentType = effectiveContentType(metadata.contentType(), file.contentType());
        reference.fileName = fileName;
        reference.targetPath = targetPath;
        reference.createdAt = java.time.ZonedDateTime.now();
        sandboxService.attachmentRepository.insert(reference);
        usedTargets.add(targetPath);
        LOGGER.info("attachment staged: session={}, reference={}, target={}, size={}",
                sessionId, reference.id, targetPath, size);
    }

    private SessionAttachmentRef existingReference(String blobName) {
        for (var reference : references) {
            if (blobName.equals(reference.blobName) && reference.targetPath != null) return reference;
        }
        return null;
    }

    private String uniqueFileName(String fileName) {
        var candidate = fileName;
        for (var attempt = 2; attempt < 100; attempt++) {
            var taken = SandboxAttachmentPath.WORKSPACE_ROOT + "/" + candidate;
            var takenTmp = SandboxAttachmentPath.ATTACHMENT_TMP_ROOT + "/" + candidate;
            if (!usedTargets.contains(taken) && !usedTargets.contains(takenTmp)) return candidate;
            candidate = suffixed(fileName, attempt);
        }
        return candidate;
    }

    private String suffixed(String fileName, int attempt) {
        var dot = fileName.lastIndexOf('.');
        if (dot <= 0) return fileName + "-" + attempt;
        return fileName.substring(0, dot) + "-" + attempt + fileName.substring(dot);
    }

    /** The original file name when usable, else the blob's own name — never a path. */
    private String fileName(PendingFile file) {
        if (SandboxAttachmentPath.valid(file.fileName())) return file.fileName();
        var blobName = file.blobName();
        if (blobName == null) return null;
        var index = blobName.lastIndexOf('/');
        var base = index >= 0 ? blobName.substring(index + 1) : blobName;
        return SandboxAttachmentPath.valid(base) ? base : null;
    }
}
