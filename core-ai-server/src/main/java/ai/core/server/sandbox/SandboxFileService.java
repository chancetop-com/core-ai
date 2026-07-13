package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    /** Upload the given files directly to the session's sandbox, bypassing the pendingFiles queue.
     *  Used when file metadata is carried in the command payload (cross-pod safe). */
    void uploadFiles(String sessionId, List<PendingFile> files) {
        if (files == null || files.isEmpty()) return;
        var sandbox = sandboxService.sessionSandbox(sessionId);
        LOGGER.info("[UPLOAD] uploadFiles called, sessionId={}, sandboxExists={}, fileCount={}",
                sessionId, sandbox != null, files.size());
        if (sandbox == null) {
            LOGGER.warn("no sandbox for session {}, cannot upload files", sessionId);
            return;
        }
        if (sandbox instanceof LazySandbox lazy) {
            lazy.ensureReady();
        }
        for (var file : files) {
            if (file.fileId() != null) {
                stageFileRecord(sandbox, sessionId, file);
            } else if (!uploadBlobFile(sandbox, sessionId, file)) {
                LOGGER.warn("failed to upload file to sandbox: session={}, file={}", sessionId, file.fileName());
            }
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
        var storageService = sandboxService.storageService;
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
