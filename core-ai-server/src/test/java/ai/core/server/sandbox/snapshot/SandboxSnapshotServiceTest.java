package ai.core.server.sandbox.snapshot;

import ai.core.server.blob.ObjectStorageService;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SandboxSnapshotServiceTest {
    private SandboxSnapshotService service;
    private MongoCollection<SandboxSnapshotDoc> snapshots;
    private MongoCollection<SandboxEpochDoc> epochs;
    private ObjectStorageService storage;
    private SandboxSnapshotClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new SandboxSnapshotService();
        snapshots = mock(MongoCollection.class);
        epochs = mock(MongoCollection.class);
        storage = mock(ObjectStorageService.class);
        client = mock(SandboxSnapshotClient.class);
        service.snapshotCollection = snapshots;
        service.epochCollection = epochs;
        service.client = client;
        service.configure(storage, "sandbox-snapshots", true);
    }

    private SandboxEpochDoc epochDoc(long epoch) {
        var doc = new SandboxEpochDoc();
        doc.id = "s1";
        doc.epoch = epoch;
        doc.updatedAt = ZonedDateTime.now();
        return doc;
    }

    private SandboxSnapshotDoc availableDoc(String runtimeVersion) {
        var doc = new SandboxSnapshotDoc();
        doc.id = "snap1";
        doc.sessionId = "s1";
        doc.userId = "u1";
        doc.epoch = 3L;
        doc.status = SandboxSnapshotDoc.STATUS_AVAILABLE;
        doc.blobKey = "u1/s1/snap1.tar.gz";
        doc.sha256 = "0".repeat(64);
        doc.runtimeVersion = runtimeVersion;
        doc.createdAt = ZonedDateTime.now().minusHours(1);
        doc.expiresAt = ZonedDateTime.now().plusDays(13);
        return doc;
    }

    @Test
    void beginEpochInsertsOnFirstUse() {
        when(epochs.update(any(Bson.class), any(Bson.class))).thenReturn(0L);
        when(epochs.get("s1")).thenReturn(Optional.of(epochDoc(1)));

        var epoch = service.beginEpoch("s1");

        assertEquals(1, epoch);
        verify(epochs).insert(any(SandboxEpochDoc.class));
    }

    @Test
    void beginEpochIncrementsExisting() {
        when(epochs.update(any(Bson.class), any(Bson.class))).thenReturn(1L);
        when(epochs.get("s1")).thenReturn(Optional.of(epochDoc(5)));

        assertEquals(5, service.beginEpoch("s1"));
        verify(epochs, never()).insert(any(SandboxEpochDoc.class));
    }

    @Test
    void restoreReturnsNoneWithoutSnapshot() {
        when(snapshots.find(any(Query.class))).thenReturn(List.of());

        assertEquals(SandboxSnapshotService.RestoreOutcome.NONE, service.restoreLatest("s1", "u1", "10.0.0.1", 8080));
    }

    @Test
    void restoreReturnsNoneOnUserMismatch() {
        when(snapshots.find(any(Query.class))).thenReturn(List.of(availableDoc("1.0.27")));

        assertEquals(SandboxSnapshotService.RestoreOutcome.NONE, service.restoreLatest("s1", "other-user", "10.0.0.1", 8080));
    }

    @Test
    void restoreReturnsNoneOnRuntimeMajorMismatch() {
        when(snapshots.find(any(Query.class))).thenReturn(List.of(availableDoc("1.0.27")));
        when(client.fetchRuntimeVersion("10.0.0.1", 8080)).thenReturn("2.0.0");

        assertEquals(SandboxSnapshotService.RestoreOutcome.NONE, service.restoreLatest("s1", "u1", "10.0.0.1", 8080));
    }

    @Test
    void restoreDegradesAfterTwoFailedAttempts() {
        var doc = availableDoc("1.0.27");
        when(snapshots.find(any(Query.class))).thenReturn(List.of(doc));
        when(client.fetchRuntimeVersion("10.0.0.1", 8080)).thenReturn("1.0.30");
        org.mockito.Mockito.doThrow(new RuntimeException("blob down"))
            .when(storage).downloadObjectToFile(anyString(), anyString(), any(Path.class));

        assertEquals(SandboxSnapshotService.RestoreOutcome.DEGRADED, service.restoreLatest("s1", "u1", "10.0.0.1", 8080));
    }

    @Test
    void captureAbandonsWhenEpochMovedOn() throws Exception {
        var result = new SandboxSnapshotClient.CaptureResult("0".repeat(64), 10, 1, "1.0.27");
        when(client.capture(anyString(), anyInt(), any(Path.class))).thenAnswer(inv -> {
            Files.writeString(inv.getArgument(2), "x");
            return result;
        });
        when(epochs.get("s1")).thenReturn(Optional.of(epochDoc(7))); // current epoch is 7, capture recorded 5

        service.captureBeforeRelease("s1", "u1", 5, "10.0.0.1", 8080, "img:latest");

        verify(snapshots).insert(any(SandboxSnapshotDoc.class));
        verify(snapshots, never()).update(any(Bson.class), eq(com.mongodb.client.model.Updates.set("status", SandboxSnapshotDoc.STATUS_AVAILABLE)));
        // Stale doc must be tombstoned so cleanup retries the blob deletion.
        verify(snapshots).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void captureSkipsWhenDisabled() {
        service.configure(storage, "sandbox-snapshots", false);

        service.captureBeforeRelease("s1", "u1", 5, "10.0.0.1", 8080, "img:latest");

        verify(snapshots, never()).insert(any(SandboxSnapshotDoc.class));
    }

    @Test
    void captureMarksAvailableAndDeletesPreviousGeneration() {
        var result = new SandboxSnapshotClient.CaptureResult("0".repeat(64), 10, 1, "1.0.27");
        when(client.capture(anyString(), anyInt(), any(Path.class))).thenAnswer(inv -> {
            Files.writeString(inv.getArgument(2), "x");
            return result;
        });
        when(epochs.get("s1")).thenReturn(Optional.of(epochDoc(5))); // current epoch still matches recorded 5
        when(snapshots.update(any(Bson.class), any(Bson.class))).thenReturn(1L);
        var oldDoc = availableDoc("1.0.27");
        oldDoc.id = "old-snap";
        oldDoc.blobKey = "u1/s1/old-snap.tar.gz";
        when(snapshots.find(any(Query.class))).thenReturn(List.of(oldDoc));

        service.captureBeforeRelease("s1", "u1", 5, "10.0.0.1", 8080, "img:latest");

        verify(snapshots).insert(any(SandboxSnapshotDoc.class));
        verify(storage).uploadObject(eq("sandbox-snapshots"), argThat(key -> key.startsWith("u1/s1/") && key.endsWith(".tar.gz")), any(Path.class));
        // The only update in this flow is the UPLOADING -> AVAILABLE CAS.
        var updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(snapshots).update(any(Bson.class), updateCaptor.capture());
        assertTrue(updateCaptor.getValue().toString().contains(SandboxSnapshotDoc.STATUS_AVAILABLE));
        // Previous-generation cleanup ran for the old doc.
        verify(storage).deleteObject("sandbox-snapshots", "u1/s1/old-snap.tar.gz");
        verify(snapshots).delete("old-snap");
    }

    @Test
    void restoreReturnsRestoredOnHappyPath() throws Exception {
        var content = "snapshot-bytes".getBytes(StandardCharsets.UTF_8);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var doc = availableDoc("1.0.27");
        doc.sha256 = sha;
        when(snapshots.find(any(Query.class))).thenReturn(List.of(doc));
        when(client.fetchRuntimeVersion("10.0.0.1", 8080)).thenReturn("1.0.30");
        doAnswer(inv -> {
            Files.write(inv.getArgument(2), content);
            return null;
        }).when(storage).downloadObjectToFile(anyString(), anyString(), any(Path.class));

        assertEquals(SandboxSnapshotService.RestoreOutcome.RESTORED, service.restoreLatest("s1", "u1", "10.0.0.1", 8080));
        verify(client).restore(eq("10.0.0.1"), eq(8080), any(Path.class), eq(sha));
    }

    @Test
    void restoreDegradesOnShaMismatch() {
        var doc = availableDoc("1.0.27"); // stored sha256 is all zeros, downloaded bytes hash differently
        when(snapshots.find(any(Query.class))).thenReturn(List.of(doc));
        when(client.fetchRuntimeVersion("10.0.0.1", 8080)).thenReturn("1.0.30");
        doAnswer(inv -> {
            Files.writeString(inv.getArgument(2), "corrupted");
            return null;
        }).when(storage).downloadObjectToFile(anyString(), anyString(), any(Path.class));

        assertEquals(SandboxSnapshotService.RestoreOutcome.DEGRADED, service.restoreLatest("s1", "u1", "10.0.0.1", 8080));
        // Corrupt data must never reach the runtime.
        verify(client, never()).restore(anyString(), anyInt(), any(Path.class), anyString());
    }
}
