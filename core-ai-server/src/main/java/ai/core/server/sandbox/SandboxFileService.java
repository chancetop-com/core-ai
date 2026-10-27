package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.file.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages sandbox file upload queue: staging blobs and workflow artifacts into sandboxes.
 *
 * @author stephen
 */
class SandboxFileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxFileService.class);
    private static final String SANDBOX_BLOB_PREFIX = "uploads/";

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

    /**
     * Stages this message's attachments (images, PDFs, videos) into the session's sandbox and records a
     * reference for each, so the agent can process the uploaded bytes locally with whatever the case needs —
     * converting a container a model refuses, cropping, extracting a frame, reading a PDF — and hand the
     * result back to a media tool. Best effort by design: a session without a sandbox, an oversized file or a
     * storage hiccup must not fail the message, because the attachment still arrives as a URL.
     *
     * @return the sandbox path staged for each attachment, keyed by blob name, so the message hint can name
     *         the file a request is about instead of letting the model guess
     */
    Map<String, String> stageAttachments(String sessionId, String userId, List<PendingFile> files) {
        if (files == null || files.isEmpty()) return Map.of();
        if (userId == null || userId.isBlank()) return Map.of();
        if (sandboxService.sessionSandbox(sessionId) == null) return Map.of();   // no sandbox in this session at all
        var repository = sandboxService.attachmentRepository;
        if (repository == null) return Map.of();
        var storage = sandboxService.storageResolver == null ? null : sandboxService.storageResolver.resolve();
        if (storage == null) return Map.of();

        List<SessionAttachmentRef> references;
        try {
            references = repository.findSandboxAttachments(sessionId, userId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to read staged attachments, staging is skipped: session={}", sessionId, e);
            return Map.of();
        }
        var staging = new AttachmentStaging(sandboxService, sessionId, userId, storage, references, attachmentLimit());
        var staged = new LinkedHashMap<String, String>();
        for (var file : files) {
            var targetPath = staging.stage(file);
            if (targetPath != null && file.blobName() != null) staged.put(file.blobName(), targetPath);
        }
        return staged;
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
        var targetPath = SandboxAttachmentPath.targetPath(SandboxAttachmentPath.CHAT_FILE_ROOT, file.fileName());
        var tempFile = createTempFile();
        try {
            var metadata = storageService.headObject(file.container(), file.blobName());
            storageService.downloadObjectToFile(file.container(), file.blobName(), tempFile);
            var size = Files.size(tempFile);
            verifyDownloadedSize(metadata.sizeBytes(), size);
            sandbox.uploadFile(targetPath, tempFile);

            var reference = new SessionAttachmentRef();
            reference.id = "sandbox_" + UUID.randomUUID();
            reference.sessionId = sessionId;
            reference.userId = userId;
            reference.kind = SessionAttachmentRef.KIND_SANDBOX;
            reference.container = file.container();
            reference.blobName = file.blobName();
            reference.sourceETag = metadata.etag();
            reference.sourceSizeBytes = metadata.sizeBytes() != null ? metadata.sizeBytes() : size;
            reference.contentType = AttachmentStaging.effectiveContentType(metadata.contentType(), file.contentType());
            reference.fileName = file.fileName();
            reference.targetPath = targetPath;
            reference.createdAt = ZonedDateTime.now();
            repository.insert(reference);
            LOGGER.info("sandbox attachment uploaded and persisted: session={}, reference={}, target={}, size={}",
                    sessionId, reference.id, targetPath, size);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to upload sandbox attachment " + file.fileName(), e);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private void validateBlobSource(String expectedContainer, PendingFile file) {
        if (file.container() == null || !file.container().equals(expectedContainer)) {
            throw new IllegalArgumentException("invalid sandbox attachment container");
        }
        if (file.blobName() == null || !file.blobName().startsWith(SANDBOX_BLOB_PREFIX)) {
            throw new IllegalArgumentException("invalid sandbox attachment blob name");
        }
        SandboxAttachmentPath.targetPath(SandboxAttachmentPath.CHAT_FILE_ROOT, file.fileName());
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
            if (!restorable(sessionId, reference, seenTargets, snapshotCreatedAt)) {
                skipped++;
                continue;
            }
            if (restore(sandbox, storage, sessionId, reference)) {
                restored++;
            } else {
                failed++;
            }
        }
        LOGGER.info("sandbox attachment restore complete: session={}, restoredCount={}, skippedCount={}, failedCount={}",
                sessionId, restored, skipped, failed);
    }

    private boolean restorable(String sessionId, SessionAttachmentRef reference, Set<String> seenTargets,
                               ZonedDateTime snapshotCreatedAt) {
        if (reference.targetPath == null || !seenTargets.add(reference.targetPath)) return false;
        if (snapshotCreatedAt != null
                && (reference.createdAt == null || !reference.createdAt.isAfter(snapshotCreatedAt))) return false;
        if (!SessionAttachmentRef.KIND_SANDBOX.equals(reference.kind)
                || !AttachmentStaging.validSource(sandboxService, reference.container, reference.blobName)
                || !SandboxAttachmentPath.isSafeTarget(reference.fileName, reference.targetPath)) {
            LOGGER.warn("invalid sandbox attachment reference skipped: session={}, reference={}, target={}",
                    sessionId, reference.id, reference.targetPath);
            return false;
        }
        return reference.sourceSizeBytes == null || reference.sourceSizeBytes <= attachmentLimit();
    }

    /** Attachments over this limit stay in platform storage; the System Configuration value wins when set. */
    long attachmentLimit() {
        var supplier = sandboxService.attachmentMaxBytesSupplier;
        if (supplier != null) {
            var configured = supplier.getAsLong();
            if (configured > 0) return configured;
        }
        return sandboxService.attachmentMaxBytes;
    }

    private boolean restore(Sandbox sandbox, ObjectStorageService storage, String sessionId,
                            SessionAttachmentRef reference) {
        var tempFile = createTempFile();
        try {
            var metadata = storage.headObject(reference.container, reference.blobName);
            verifySourceVersion(reference, metadata.etag(), metadata.sizeBytes());
            storage.downloadObjectToFile(reference.container, reference.blobName, tempFile);
            verifyDownloadedSize(reference.sourceSizeBytes, Files.size(tempFile));
            sandbox.uploadFile(reference.targetPath, tempFile);
            return true;
        } catch (Exception e) {
            LOGGER.warn("failed to restore sandbox attachment: session={}, reference={}, target={}", sessionId, reference.id, reference.targetPath, e);
            return false;
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private void verifySourceVersion(SessionAttachmentRef reference, String currentETag, Long currentSize) {
        if (reference.sourceETag != null && !reference.sourceETag.equals(currentETag)) {
            throw new IllegalStateException("sandbox attachment source ETag changed");
        }
        if (reference.sourceSizeBytes != null && !reference.sourceSizeBytes.equals(currentSize)) {
            throw new IllegalStateException("sandbox attachment source size changed");
        }
    }

    private void verifyDownloadedSize(Long expectedSize, long actualSize) {
        if (expectedSize != null && !expectedSize.equals(actualSize)) {
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
        var tempFile = createTempFile();
        try {
            var record = fileService.get(file.fileId());
            downloadRecord(fileService, record, tempFile);
            var size = Files.size(tempFile);
            sandbox.uploadFile(file.targetPath(), tempFile);
            LOGGER.info("staged file uploaded: session={}, target={}, size={}", sessionId, file.targetPath(), size);
        } catch (RuntimeException | IOException e) {
            throw new IllegalStateException("failed to stage input file " + file.fileName() + " into sandbox: " + e.getMessage(), e);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private void downloadRecord(FileService fileService, FileRecord record, Path tempFile) throws IOException {
        var storage = sandboxService.storageResolver == null ? null : sandboxService.storageResolver.resolve();
        if (record.storagePath != null && storage != null) {
            storage.downloadObjectToFile(containerOf(record.storagePath), blobOf(record.storagePath), tempFile);
            return;
        }
        Files.write(tempFile, fileService.getBytes(record));
    }

    private String containerOf(String storagePath) {
        return storagePath.substring(0, storagePath.indexOf('/'));
    }

    private String blobOf(String storagePath) {
        return storagePath.substring(storagePath.indexOf('/') + 1);
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
            uploadToSandbox(sandbox, storageService, file);
            LOGGER.info("pending file uploaded: session={}, file={}", sessionId, file.fileName());
            return true;
        } catch (Exception e) {
            LOGGER.error("failed to upload pending file to sandbox: session={}, file={}", sessionId, file.fileName(), e);
            return false;
        }
    }

    private void uploadToSandbox(Sandbox sandbox, ObjectStorageService storageService, PendingFile file) throws IOException {
        LOGGER.info("[UPLOAD] downloading blob: container={}, blobName={}", file.container(), file.blobName());
        var tempFile = createTempFile();
        try {
            storageService.downloadObjectToFile(file.container(), file.blobName(), tempFile);
            LOGGER.info("[UPLOAD] blob downloaded: size={} bytes, uploading to /tmp/{}", Files.size(tempFile), file.fileName());
            sandbox.uploadFile("/tmp/" + file.fileName(), tempFile);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private Path createTempFile() {
        try {
            return Files.createTempFile("sandbox-upload-", ".bin");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create a temp file for a sandbox upload", e);
        }
    }

    private void deleteTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            LOGGER.warn("failed to delete temp file, path={}", tempFile, e);
        }
    }

    void clear(String sessionId) {
        pendingFiles.remove(sessionId);
    }

    void clearAll() {
        pendingFiles.clear();
    }
}
