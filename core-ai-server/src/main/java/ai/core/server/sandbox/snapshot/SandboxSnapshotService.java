package ai.core.server.sandbox.snapshot;

import ai.core.server.blob.ObjectStorageService;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
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
    private static final int EXPIRES_DAYS = 14;

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
    MongoCollection<SandboxSnapshotDoc> snapshotCollection;
    @Inject
    MongoCollection<SandboxEpochDoc> epochCollection;

    SandboxSnapshotClient client = new SandboxSnapshotClient();

    private ObjectStorageService storage;
    private String container;
    private boolean enabled;

    public void configure(ObjectStorageService storage, String container, boolean enabled) {
        this.storage = storage;
        this.container = container;
        this.enabled = enabled;
        LOGGER.info("sandbox snapshot configured: enabled={}, container={}", enabled(), container);
    }

    public boolean enabled() {
        return enabled && storage != null;
    }

    /**
     * Increment and return the session's sandbox epoch. The read-after-inc is not
     * atomic; a concurrent acquire can only make our recorded value HIGHER than the
     * increment we own, which makes the capture-time epoch check stricter, never looser.
     */
    public long beginEpoch(String sessionId) {
        if (!enabled()) return 0;
        var now = ZonedDateTime.now();
        var update = Updates.combine(Updates.inc("epoch", 1), Updates.set("updated_at", now));
        long modified = epochCollection.update(Filters.eq("_id", sessionId), update);
        if (modified == 0) {
            var doc = new SandboxEpochDoc();
            doc.id = sessionId;
            doc.epoch = 1L;
            doc.updatedAt = now;
            try {
                epochCollection.insert(doc);
            } catch (MongoException e) {
                // Another pod inserted concurrently; fall through to increment it.
                epochCollection.update(Filters.eq("_id", sessionId), update);
            }
        }
        return epochCollection.get(sessionId).map(d -> d.epoch).orElse(1L);
    }

    public RestoreOutcome restoreLatest(String sessionId, String userId, String ip, int port) {
        // Caller identity is mandatory for restore.
        if (!enabled() || userId == null) return RestoreOutcome.NONE;
        SandboxSnapshotDoc doc;
        try {
            doc = findLatestAvailable(sessionId);
        } catch (Exception e) {
            LOGGER.warn("snapshot lookup failed, continuing with empty sandbox: session={}", sessionId, e);
            return RestoreOutcome.NONE;
        }
        if (doc == null) return RestoreOutcome.NONE;
        if (!userId.equals(doc.userId)) {
            LOGGER.warn("snapshot user mismatch, skip restore: session={}, snapshot={}", sessionId, doc.id);
            return RestoreOutcome.NONE;
        }
        if (doc.expiresAt != null && doc.expiresAt.isBefore(ZonedDateTime.now())) return RestoreOutcome.NONE;
        var liveVersion = client.fetchRuntimeVersion(ip, port);
        if (majorOf(liveVersion) != majorOf(doc.runtimeVersion)) {
            LOGGER.warn("snapshot runtime major mismatch, skip restore: session={}, snapshot={}, live={}, stored={}",
                    sessionId, doc.id, liveVersion, doc.runtimeVersion);
            return RestoreOutcome.NONE;
        }
        // Retry once: the second attempt absorbs transient network/blob hiccups.
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                downloadVerifyAndRestore(doc, ip, port);
                LOGGER.info("snapshot restored: session={}, snapshot={}, attempt={}", sessionId, doc.id, attempt);
                return RestoreOutcome.RESTORED;
            } catch (Exception e) {
                LOGGER.warn("snapshot restore attempt {} failed: session={}, snapshot={}", attempt, sessionId, doc.id, e);
            }
        }
        return RestoreOutcome.DEGRADED;
    }

    /** Capture the sandbox filesystem before release. Never throws: release must proceed regardless. */
    public void captureBeforeRelease(String sessionId, String userId, long epoch, String ip, int port, String image) {
        if (!enabled()) return;
        Path tmp = null;
        try {
            tmp = Files.createTempFile("sandbox-capture-", ".tar.gz");
            var captured = client.capture(ip, port, tmp);
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
            doc.expiresAt = doc.createdAt.plusDays(EXPIRES_DAYS);
            snapshotCollection.insert(doc);
            storage.uploadObject(container, doc.blobKey, tmp);

            long currentEpoch = epochCollection.get(sessionId).map(d -> d.epoch).orElse(-1L);
            if (currentEpoch != epoch) {
                // The session already resumed on a newer sandbox: this capture is stale.
                tombstone(doc.id);
                LOGGER.info("stale snapshot discarded: session={}, snapshot={}, capturedEpoch={}, currentEpoch={}",
                        sessionId, doc.id, epoch, currentEpoch);
                return;
            }
            long updated = snapshotCollection.update(
                    Filters.and(Filters.eq("_id", doc.id), Filters.eq("status", SandboxSnapshotDoc.STATUS_UPLOADING)),
                    Updates.set("status", SandboxSnapshotDoc.STATUS_AVAILABLE));
            if (updated == 0) return;
            LOGGER.info("snapshot available: session={}, snapshot={}, files={}, size={}",
                    sessionId, doc.id, doc.fileCount, doc.sizeBytes);
            deletePreviousGenerations(sessionId, doc.id);
        } catch (Exception e) {
            LOGGER.warn("snapshot capture failed, releasing sandbox without snapshot: session={}", sessionId, e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** Expired docs (and tombstones, whose expires_at is forced to now) — delete blob then doc. */
    public int cleanupExpired() {
        if (!enabled()) return 0;
        var query = new Query();
        query.filter = Filters.lt("expires_at", ZonedDateTime.now());
        query.limit = 100;
        var expired = snapshotCollection.find(query);
        int cleaned = 0;
        for (var doc : expired) {
            try {
                storage.deleteObject(container, doc.blobKey);
                snapshotCollection.delete(doc.id);
                cleaned++;
            } catch (Exception e) {
                LOGGER.warn("snapshot cleanup failed, will retry next cycle: snapshot={}", doc.id, e);
            }
        }
        return cleaned;
    }

    /** Called when the user deletes a chat session. Best-effort; leftovers expire via cleanup. */
    public void deleteForSession(String sessionId) {
        if (!enabled()) return;
        try {
            var query = new Query();
            query.filter = Filters.eq("session_id", sessionId);
            for (var doc : snapshotCollection.find(query)) {
                deleteOrTombstone(doc);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to delete snapshots for session={}", sessionId, e);
        }
    }

    private SandboxSnapshotDoc findLatestAvailable(String sessionId) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("session_id", sessionId),
                Filters.eq("status", SandboxSnapshotDoc.STATUS_AVAILABLE));
        query.sort = Sorts.descending("created_at");
        query.limit = 1;
        var docs = snapshotCollection.find(query);
        return docs.isEmpty() ? null : docs.getFirst();
    }

    private void downloadVerifyAndRestore(SandboxSnapshotDoc doc, String ip, int port) throws Exception {
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

    private void deletePreviousGenerations(String sessionId, String currentId) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("session_id", sessionId),
                Filters.eq("status", SandboxSnapshotDoc.STATUS_AVAILABLE));
        for (var doc : snapshotCollection.find(query)) {
            if (currentId.equals(doc.id)) continue;
            deleteOrTombstone(doc);
        }
    }

    /** Delete the blob and doc; if either fails, tombstone so cleanup retries later. */
    private void deleteOrTombstone(SandboxSnapshotDoc doc) {
        try {
            storage.deleteObject(container, doc.blobKey);
            snapshotCollection.delete(doc.id);
        } catch (Exception e) {
            tombstone(doc.id);
        }
    }

    /** Mark DELETED and force-expire so the cleanup job retries the blob deletion. */
    private void tombstone(String snapshotId) {
        snapshotCollection.update(Filters.eq("_id", snapshotId), Updates.combine(
                Updates.set("status", SandboxSnapshotDoc.STATUS_DELETED),
                Updates.set("expires_at", ZonedDateTime.now())));
    }

    private String sha256Hex(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
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
}
