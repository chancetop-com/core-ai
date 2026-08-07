package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.server.domain.SessionAttachmentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages sandbox file upload queue: staging blobs and workflow artifacts into sandboxes.
 *
 * @author stephen
 */
class SandboxFileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxFileService.class);

    private final Map<String, List<PendingFile>> pendingFiles = new ConcurrentHashMap<>();
    private final SandboxService sandboxService;

    SandboxFileService(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    void addPendingFile(String sessionId, String fileName, String container, String blobName) {
        pendingFiles.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new PendingFile(fileName, container, blobName));
        LOGGER.info("[ENQUEUE] pending file added: session={}, file={}, blob={}/{}, totalFilesForSession={}",
                sessionId, fileName, container, blobName, pendingFiles.get(sessionId).size());
    }

    /** Queue a workflow input file (a FileRecord) to be staged at {@code targetPath} in the session's sandbox. */
    void addStagedFile(String sessionId, StagedFile file) {
        pendingFiles.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new PendingFile(file.fileName(), null, null, file.fileId(), file.targetPath()));
        LOGGER.info("staged file queued: session={}, file={}, target={}", sessionId, file.fileName(), file.targetPath());
    }

    void ensurePendingFilesUploaded(String sessionId) {
        var files = pendingFiles.get(sessionId);
        if (files == null || files.isEmpty()) return;
        var sandbox = sandboxService.sessionSandbox(sessionId);
        if (sandbox == null) {
            LOGGER.info("no sandbox for session {}, pending files will be uploaded when sandbox is created", sessionId);
            return;
        }
        if (sandbox instanceof LazySandbox lazy) {
            LOGGER.info("[UPLOAD] calling lazy.ensureReady(), sandboxId={}, status={}", lazy.getId(), lazy.getStatus());
            lazy.ensureReady();
            LOGGER.info("[UPLOAD] lazy.ensureReady() returned, sandboxId={}, status={}", lazy.getId(), lazy.getStatus());
        }
        uploadPendingFiles(sessionId);
    }

    /** Uploads chat attachments and persists their source references after the Sandbox write succeeds. */
    void uploadFiles(String sessionId, String userId, List<PendingFile> files) {
        if (files == null || files.isEmpty()) return;
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user id is required for sandbox attachments");
        }
        var resolver = sandboxService.storageResolver;
        if (resolver == null || sandboxService.attachmentRepository == null) {
            throw new IllegalStateException("sandbox attachment persistence is not configured");
        }
        for (var file : files) {
            if (file.fileId() == null) validateBlobSource(resolver.sandboxContainer(), file);
        }
        var sandbox = sandboxService.sessionSandbox(sessionId);
        if (sandbox == null) {
            throw new IllegalStateException("no sandbox for session " + sessionId);
        }
        if (sandbox instanceof LazySandbox lazy) lazy.ensureReady();
        for (var file : files) {
            if (file.fileId() != null) {
                stageFileRecord(sandbox, sessionId, file);
            } else {
                uploadAndPersistBlobFile(sandbox, sessionId, userId, file);
            }
        }
    }

    private void uploadAndPersistBlobFile(Sandbox sandbox, String sessionId, String userId, PendingFile file) {
        var resolver = sandboxService.storageResolver;
        var repository = sandboxService.attachmentRepository;
        if (resolver == null || repository == null) {
            throw new IllegalStateException("sandbox attachment persistence is not configured");
        }
        validateBlobSource(resolver.sandboxContainer(), file);
        var storageService = resolver.resolve();
        if (storageService == null) throw new IllegalStateException("object storage is not configured");
        var targetPath = SandboxAttachmentPath.targetPath(file.fileName());
        try {
            var metadata = storageService.headObject(file.container(), file.blobName());
            var data = storageService.downloadObject(file.container(), file.blobName());
            verifyDownloadedSize(metadata.sizeBytes(), data.length);
            sandbox.uploadFile(targetPath, data);

            var reference = new SessionAttachmentRef();
            reference.id = "sandbox_" + UUID.randomUUID();
            reference.sessionId = sessionId;
            reference.userId = userId;
            reference.kind = SessionAttachmentRef.KIND_SANDBOX;
            reference.container = file.container();
            reference.blobName = file.blobName();
            reference.sourceETag = metadata.etag();
            reference.sourceSizeBytes = metadata.sizeBytes() != null ? metadata.sizeBytes() : (long) data.length;
            reference.contentType = effectiveContentType(metadata.contentType(), file.contentType());
            reference.fileName = file.fileName();
            reference.targetPath = targetPath;
            reference.createdAt = ZonedDateTime.now();
            repository.insert(reference);
            LOGGER.info("sandbox attachment uploaded and persisted: session={}, reference={}, target={}, size={}",
                    sessionId, reference.id, targetPath, data.length);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to upload sandbox attachment " + file.fileName(), e);
        }
    }

    private void validateBlobSource(String expectedContainer, PendingFile file) {
        if (file.container() == null || !file.container().equals(expectedContainer)) {
            throw new IllegalArgumentException("invalid sandbox attachment container");
        }
        if (file.blobName() == null || !file.blobName().startsWith("uploads/")) {
            throw new IllegalArgumentException("invalid sandbox attachment blob name");
        }
        SandboxAttachmentPath.targetPath(file.fileName());
    }

    private String effectiveContentType(String storageContentType, String requestContentType) {
        if (storageContentType != null && !storageContentType.isBlank()) return storageContentType;
        if (requestContentType != null && !requestContentType.isBlank()) return requestContentType;
        return "application/octet-stream";
    }

    void restoreAttachments(String sessionId, String userId, ZonedDateTime snapshotCreatedAt) {
        var repository = sandboxService.attachmentRepository;
        var resolver = sandboxService.storageResolver;
        if (repository == null || resolver == null || userId == null) return;
        var sandbox = sandboxService.sessionSandbox(sessionId);
        var storage = resolver.resolve();
        if (sandbox == null || storage == null) return;

        List<SessionAttachmentRef> references;
        try {
            references = repository.findSandboxAttachments(sessionId, userId);
        } catch (Exception e) {
            LOGGER.error("failed to query sandbox attachments for restore: session={}", sessionId, e);
            return;
        }
        var seenTargets = new HashSet<String>();
        var restored = 0;
        var skipped = 0;
        var failed = 0;
        for (var reference : references) {
            if (reference.targetPath == null || !seenTargets.add(reference.targetPath)) {
                skipped++;
                continue;
            }
            if (snapshotCreatedAt != null
                    && (reference.createdAt == null || !reference.createdAt.isAfter(snapshotCreatedAt))) {
                skipped++;
                continue;
            }
            if (!validRestoreReference(resolver.sandboxContainer(), reference)) {
                skipped++;
                LOGGER.warn("invalid sandbox attachment reference skipped: session={}, reference={}, target={}",
                        sessionId, reference.id, reference.targetPath);
                continue;
            }
            try {
                var metadata = storage.headObject(reference.container, reference.blobName);
                verifySourceVersion(reference, metadata.etag(), metadata.sizeBytes());
                var data = storage.downloadObject(reference.container, reference.blobName);
                verifyDownloadedSize(reference.sourceSizeBytes, data.length);
                sandbox.uploadFile(reference.targetPath, data);
                restored++;
            } catch (Exception e) {
                failed++;
                LOGGER.warn("failed to restore sandbox attachment: session={}, reference={}, target={}",
                        sessionId, reference.id, reference.targetPath, e);
            }
        }
        LOGGER.info("sandbox attachment restore complete: session={}, restoredCount={}, skippedCount={}, failedCount={}",
                sessionId, restored, skipped, failed);
    }

    private boolean validRestoreReference(String expectedContainer, SessionAttachmentRef reference) {
        return expectedContainer != null
                && reference.blobName != null
                && SessionAttachmentRef.KIND_SANDBOX.equals(reference.kind)
                && expectedContainer.equals(reference.container)
                && reference.blobName.startsWith("uploads/")
                && SandboxAttachmentPath.isSafeTarget(reference.fileName, reference.targetPath);
    }

    private void verifySourceVersion(SessionAttachmentRef reference, String currentETag, Long currentSize) {
        if (reference.sourceETag != null && !reference.sourceETag.equals(currentETag)) {
            throw new IllegalStateException("sandbox attachment source ETag changed");
        }
        if (reference.sourceSizeBytes != null && !reference.sourceSizeBytes.equals(currentSize)) {
            throw new IllegalStateException("sandbox attachment source size changed");
        }
    }

    private void verifyDownloadedSize(Long expectedSize, int actualSize) {
        if (expectedSize != null && expectedSize != actualSize) {
            throw new IllegalStateException("sandbox attachment download size changed");
        }
    }

    private void uploadPendingFiles(String sessionId) {
        var files = pendingFiles.get(sessionId);
        LOGGER.info("[UPLOAD] uploadPendingFiles called, sessionId={}, filesExist={}, fileCount={}",
                sessionId, files != null, files != null ? files.size() : 0);
        if (files == null || files.isEmpty()) {
            LOGGER.info("[UPLOAD] no pending files for sessionId={}, total pending sessions={}", sessionId, pendingFiles.size());
            return;
        }

        var sandbox = sandboxService.sessionSandbox(sessionId);
        if (sandbox == null) {
            LOGGER.warn("no sandbox found for session {} when uploading pending files", sessionId);
            return;
        }

        LOGGER.info("[UPLOAD] uploading {} files for sessionId={}, sandboxId={}", files.size(), sessionId, sandbox.getId());
        for (var file : files) {
            if (file.fileId() != null) {
                // workflow artifact staging: a failure here must be deterministic for the consumer, so it throws
                // (the caller — ensurePendingFilesUploaded before the agent loop / CODE executor — fails the run)
                stageFileRecord(sandbox, sessionId, file);
            } else if (!uploadBlobFile(sandbox, sessionId, file)) {
                return;   // chat upload path keeps its original behavior: abort, keep the queue, retry on next trigger
            }
        }
        pendingFiles.remove(sessionId);
        LOGGER.info("[UPLOAD] all {} files uploaded and removed from queue, sessionId={}", files.size(), sessionId);
    }

    private void stageFileRecord(Sandbox sandbox, String sessionId, PendingFile file) {
        var fileService = sandboxService.fileService;
        if (fileService == null) {
            throw new IllegalStateException("fileService not configured, cannot stage input file " + file.fileName());
        }
        try {
            var data = fileService.getBytes(fileService.get(file.fileId()));
            sandbox.uploadFile(file.targetPath(), data);
            LOGGER.info("staged file uploaded: session={}, target={}, size={}", sessionId, file.targetPath(), data.length);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to stage input file " + file.fileName() + " into sandbox: " + e.getMessage(), e);
        }
    }

    private boolean uploadBlobFile(Sandbox sandbox, String sessionId, PendingFile file) {
        var resolver = sandboxService.storageResolver;
        if (resolver == null) return false;
        var storageService = resolver.resolve();
        if (storageService == null) {
            LOGGER.warn("storageService not configured, cannot upload pending files for session {}", sessionId);
            return false;
        }
        try {
            LOGGER.info("[UPLOAD] downloading blob: container={}, blobName={}", file.container(), file.blobName());
            var data = storageService.downloadObject(file.container(), file.blobName());
            LOGGER.info("[UPLOAD] blob downloaded: size={} bytes, uploading to /tmp/{}", data.length, file.fileName());
            sandbox.uploadFile("/tmp/" + file.fileName(), data);
            LOGGER.info("pending file uploaded: session={}, file={}", sessionId, file.fileName());
            return true;
        } catch (Exception e) {
            LOGGER.error("failed to upload pending file to sandbox: session={}, file={}", sessionId, file.fileName(), e);
            return false;
        }
    }

    void clear(String sessionId) {
        pendingFiles.remove(sessionId);
    }

    void clearAll() {
        pendingFiles.clear();
    }
}
