package ai.core.server.sandbox.snapshot;

import ai.core.server.blob.MinioObjectStorageService;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.settings.SandboxSnapshotSettingsService;
import core.framework.inject.Inject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Sandbox filesystem snapshot orchestration (v1, see design doc chapter
 * "v1 实施范围"). Best-effort semantics: capture failures never block release,
 * restore failures degrade to an empty sandbox with a READY warning message.
 * Correctness rests on the per-session epoch counter plus two-phase blob
 * visibility (UPLOADING -> AVAILABLE), not on any cross-pod lease.
 *
 * @author xander
 */
public class SandboxSnapshotService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxSnapshotService.class);

    static int majorOf(String version) {
        if (version == null || version.isBlank() || "unknown".equals(version)) return -1;
        var head = version.split("\\.", 2)[0];
        try {
            return Integer.parseInt(head.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Inject
    SandboxSnapshotStore snapshotStore;
    @Inject
    SandboxEpochStore epochStore;
    @Inject
    SandboxSnapshotPolicy policy;
    @Inject
    SandboxSnapshotSettingsService settings;

    SandboxSnapshotClient client = new SandboxSnapshotClient();

    private String snapshotContainer = "sandbox-snapshots";
    private String minioSnapshotBucket = "sandbox-snapshots";

    public void configure(String snapshotContainer, String minioSnapshotBucket) {
        this.snapshotContainer = snapshotContainer;
        this.minioSnapshotBucket = minioSnapshotBucket;
        LOGGER.info("sandbox snapshot configured: container={}", snapshotContainer);
    }

    public boolean enabled() {
        return policy != null && policy.status().effective();
    }

    private String container(ObjectStorageService storage) {
        return storage instanceof MinioObjectStorageService ? minioSnapshotBucket : snapshotContainer;
    }

    /** Atomically claim and return the next session sandbox epoch. */
    public long beginEpoch(String sessionId) {
        var decision = policy == null ? null : policy.decision();
        if (decision == null || !decision.status().effective() || decision.storage() == null) {
            logInactive("beginEpoch", decision);
            return 0;
        }
        while (true) {
            var existing = epochStore.get(sessionId);
            if (existing.isPresent()) {
                var current = existing.get().epoch;
                var next = current + 1;
                if (epochStore.compareAndIncrement(sessionId, current, next)) return next;
                continue;
            }
            var doc = new SandboxEpochDoc();
            doc.id = sessionId;
            doc.epoch = 1L;
            doc.updatedAt = ZonedDateTime.now();
            if (epochStore.insertIfAbsent(doc)) return 1L;
            if (epochStore.get(sessionId).isEmpty()) {
                throw new IllegalStateException("failed to create sandbox epoch, sessionId=" + sessionId);
            }
        }
    }

    public RestoreOutcome restoreLatest(String sessionId, String userId, String ip, int port) {
        return restoreLatestWithMetadata(sessionId, userId, ip, port).outcome();
    }

    public RestoreResult restoreLatestWithMetadata(String sessionId, String userId, String ip, int port) {
        // Caller identity is mandatory for restore.
        var decision = policy == null ? null : policy.decision();
        if (decision == null || !decision.status().effective() || decision.storage() == null) {
            logInactive("restoreLatest", decision);
            return new RestoreResult(RestoreOutcome.NONE, null);
        }
        if (userId == null) return new RestoreResult(RestoreOutcome.NONE, null);
        var storage = decision.storage();
        var container = container(storage);
        SandboxSnapshotDoc doc;
        try {
            doc = findLatestAvailable(sessionId);
        } catch (Exception e) {
            LOGGER.warn("snapshot lookup failed, continuing with empty sandbox: session={}", sessionId, e);
            return new RestoreResult(RestoreOutcome.NONE, null);
        }
        if (doc == null) return new RestoreResult(RestoreOutcome.NONE, null);
        if (!userId.equals(doc.userId)) {
            LOGGER.warn("snapshot user mismatch, skip restore: session={}, snapshot={}", sessionId, doc.id);
            return new RestoreResult(RestoreOutcome.NONE, null);
        }
        if (doc.expiresAt != null && doc.expiresAt.isBefore(ZonedDateTime.now())) {
            return new RestoreResult(RestoreOutcome.NONE, null);
        }
        var liveVersion = client.fetchRuntimeVersion(ip, port);
        if (majorOf(liveVersion) != majorOf(doc.runtimeVersion)) {
            LOGGER.warn("snapshot runtime major mismatch, skip restore: session={}, snapshot={}, live={}, stored={}",
                    sessionId, doc.id, liveVersion, doc.runtimeVersion);
            return new RestoreResult(RestoreOutcome.NONE, null);
        }
        // Retry once: the second attempt absorbs transient network/blob hiccups.
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                downloadVerifyAndRestore(doc, ip, port, storage, container);
                LOGGER.info("snapshot restored: session={}, snapshot={}, attempt={}", sessionId, doc.id, attempt);
                return new RestoreResult(RestoreOutcome.RESTORED, doc.createdAt);
            } catch (Exception e) {
                LOGGER.warn("snapshot restore attempt {} failed: session={}, snapshot={}", attempt, sessionId, doc.id, e);
            }
        }
        return new RestoreResult(RestoreOutcome.DEGRADED, null);
    }

    /**
     * Capture the sandbox filesystem before release. Never throws: release must proceed regardless.
     *
     * @return true when a snapshot was stored, capture is disabled by deployment, or there was nothing
     * to store; false when capture is enabled but could not complete — callers that may abort the
     * release (quota eviction) must keep the sandbox in that case.
     */
    public boolean captureBeforeRelease(String sessionId, String userId, long epoch, String ip, int port, String image) {
        var decision = policy == null ? null : policy.decision();
        if (decision == null || !decision.status().effective() || decision.storage() == null) {
            logInactive("captureBeforeRelease", decision);
            return true;
        }
        if (epoch <= 0) {
            LOGGER.debug("sandbox snapshot capture skipped: invalid epoch");
            return false;
        }
        var storage = decision.storage();
        var container = container(storage);
        Path tmp = null;
        try {
            tmp = Files.createTempFile("sandbox-capture-", ".tar.gz");
            var captured = client.capture(ip, port, tmp);
            var doc = newUploadingSnapshot(sessionId, userId, epoch, image, captured);
            snapshotStore.insert(doc);
            storage.uploadObject(container, doc.blobKey, tmp);

            long currentEpoch = epochStore.get(sessionId).map(d -> d.epoch).orElse(-1L);
            if (currentEpoch != epoch) {
                // The session already resumed on a newer sandbox: this capture is stale.
                tombstone(doc.id);
                LOGGER.info("stale snapshot discarded: session={}, snapshot={}, capturedEpoch={}, currentEpoch={}",
                        sessionId, doc.id, epoch, currentEpoch);
                return true;
            }
            if (!snapshotStore.markAvailable(doc.id)) return false;
            LOGGER.info("snapshot available: session={}, snapshot={}, files={}, size={}",
                    sessionId, doc.id, doc.fileCount, doc.sizeBytes);
            deletePreviousGenerations(sessionId, doc.id, storage, container);
            return true;
        } catch (Exception e) {
            LOGGER.warn("snapshot capture failed, releasing sandbox without snapshot: session={}", sessionId, e);
            return false;
        } finally {
            deleteQuietly(tmp);
        }
    }

    private SandboxSnapshotDoc newUploadingSnapshot(String sessionId, String userId, long epoch, String image,
                                                     SandboxSnapshotClient.CaptureResult captured) {
        var doc = new SandboxSnapshotDoc();
        doc.id = UUID.randomUUID().toString();
        doc.sessionId = sessionId;
        doc.userId = userId;
        doc.epoch = epoch;
        doc.status = SandboxSnapshotDoc.STATUS_UPLOADING;
        doc.blobKey = userId + "/" + sessionId + "/" + doc.id + ".tar.gz";
        doc.sha256 = captured.sha256();
        doc.sizeBytes = captured.sizeBytes();
        doc.fileCount = captured.fileCount();
        doc.image = image;
        doc.runtimeVersion = captured.runtimeVersion();
        doc.createdAt = ZonedDateTime.now();
        doc.expiresAt = doc.createdAt.plusDays(settings.retentionDays());
        return doc;
    }

    /** Expired docs (and tombstones, whose expires_at is forced to now) — delete blob then doc. */
    public int cleanupExpired() {
        var decision = policy == null ? null : policy.decision();
        if (decision == null || decision.storage() == null) {
            LOGGER.debug("sandbox snapshot cleanup skipped: storage unavailable");
            return 0;
        }
        var storage = decision.storage();
        var container = container(storage);
        var expired = snapshotStore.listExpired(ZonedDateTime.now(), 100);
        int cleaned = 0;
        for (var doc : expired) {
            try {
                storage.deleteObject(container, doc.blobKey);
                snapshotStore.delete(doc.id);
                cleaned++;
            } catch (Exception e) {
                LOGGER.warn("snapshot cleanup failed, will retry next cycle: snapshot={}", doc.id, e);
            }
        }
        return cleaned;
    }

    /** Called when the user deletes a chat session. Best-effort; leftovers expire via cleanup. */
    public void deleteForSession(String sessionId) {
        var decision = policy == null ? null : policy.decision();
        if (decision == null || decision.storage() == null) {
            LOGGER.debug("sandbox snapshot session deletion skipped: storage unavailable");
            return;
        }
        var storage = decision.storage();
        var container = container(storage);
        try {
            for (var doc : snapshotStore.listBySession(sessionId)) {
                deleteOrTombstone(doc, storage, container);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to delete snapshots for session={}", sessionId, e);
        }
    }

    private SandboxSnapshotDoc findLatestAvailable(String sessionId) {
        return snapshotStore.findLatestAvailable(sessionId);
    }

    private void downloadVerifyAndRestore(SandboxSnapshotDoc doc, String ip, int port,
                                          ObjectStorageService storage, String container) throws Exception {
        var tmp = Files.createTempFile("sandbox-restore-", ".tar.gz");
        try {
            storage.downloadObjectToFile(container, doc.blobKey, tmp);
            var actual = sha256Hex(tmp);
            if (!actual.equalsIgnoreCase(doc.sha256)) {
                throw new IllegalStateException("snapshot sha256 mismatch: snapshot=" + doc.id);
            }
            client.restore(ip, port, tmp, doc.sha256);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private void deletePreviousGenerations(String sessionId, String currentId,
                                           ObjectStorageService storage, String container) {
        for (var doc : snapshotStore.listBySession(sessionId)) {
            if (!SandboxSnapshotDoc.STATUS_AVAILABLE.equals(doc.status) || currentId.equals(doc.id)) continue;
            deleteOrTombstone(doc, storage, container);
        }
    }

    /** Delete the blob and doc; if either fails, tombstone so cleanup retries later. */
    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    private void deleteOrTombstone(SandboxSnapshotDoc doc, ObjectStorageService storage, String container) {
        try {
            storage.deleteObject(container, doc.blobKey);
            snapshotStore.delete(doc.id);
        } catch (Exception e) {
            tombstone(doc.id);
        }
    }

    /** Mark DELETED and force-expire so the cleanup job retries the blob deletion. */
    private void tombstone(String snapshotId) {
        snapshotStore.tombstone(snapshotId);
    }

    private void logInactive(String operation, SandboxSnapshotPolicy.Decision decision) {
        if (decision == null) {
            LOGGER.debug("sandbox snapshot {} skipped: policy unavailable", operation);
            return;
        }
        var status = decision.status();
        LOGGER.debug("sandbox snapshot {} skipped: requested={}, deploymentAllowed={}, storageReady={}, effective={}",
                operation, status.requestedEnabled(), status.deploymentAllowed(), status.storageReady(), status.effective());
    }

    private String sha256Hex(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[64 * 1024];
            int read;
            while (true) {
                read = in.read(buffer);
                if (read == -1) break;
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            LOGGER.warn("failed to delete temp file: {}", path, e);
        }
    }

    public enum RestoreOutcome {
        NONE, RESTORED, DEGRADED
    }

    public record RestoreResult(RestoreOutcome outcome, ZonedDateTime snapshotCreatedAt) {
    }
}

