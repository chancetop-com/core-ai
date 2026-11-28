package ai.core.cli.hub.skill;

import ai.core.api.server.skillhub.SkillTelemetryBatchRequest;
import ai.core.skill.SkillMetadata;
import ai.core.skill.telemetry.SkillTelemetryIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for CliSkillTelemetryUploader non-blocking session exit budget, bounded offline queue
 * replay, batch chunking, and hub-marker privacy filtering.
 *
 * @author stephen
 */
class CliSkillTelemetryUploaderTest {
    @Test
    void flushAsyncReturnsUnderFiftyMillisEvenWhenServerIsSlowOrUnreachable(@TempDir Path tempDir) {
        var queueFile = tempDir.resolve("telemetry.jsonl");
        var uploader = new CliSkillTelemetryUploader(batch -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("server unreachable");
        }, queueFile, 16, 10);

        uploader.recordInvoke(new SkillTelemetryIdentity("skill-1", "team/demo", "sha-1"));

        long startNs = System.nanoTime();
        uploader.flushAsync();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertTrue(elapsedMs < 50L, "flushAsync blocked for " + elapsedMs + "ms (budget < 50ms)");
    }

    @Test
    void failedUploadsPersistToBoundedOfflineQueueAndReplayWithSameBatchId(@TempDir Path tempDir) throws Exception {
        var queueFile = tempDir.resolve("telemetry-queue.jsonl");
        var shouldFail = new AtomicBoolean(true);
        var failedCount = new CountDownLatch(5);
        var replayed = new CopyOnWriteArrayList<SkillTelemetryBatchRequest>();
        var replayedLatch = new CountDownLatch(3);

        var uploader = new CliSkillTelemetryUploader(batch -> {
            if (shouldFail.get()) {
                failedCount.countDown();
                throw new IllegalStateException("offline");
            }
            replayed.add(batch);
            replayedLatch.countDown();
        }, queueFile, 16, 3);

        for (int i = 0; i < 5; i++) {
            uploader.recordInvoke(new SkillTelemetryIdentity("skill-" + i, "team/s-" + i, "sha-" + i));
            uploader.flushAsync();
        }
        assertTrue(failedCount.await(5, TimeUnit.SECONDS));
        waitUntilDroppedOfflineCount(uploader, 2L);
        assertEquals(2L, uploader.droppedOfflineBatchCount());

        var queuedBeforeReplay = Files.readAllLines(queueFile);
        assertEquals(3, queuedBeforeReplay.size());

        shouldFail.set(false);
        uploader.replayOfflineAsync();
        assertTrue(replayedLatch.await(5, TimeUnit.SECONDS));
        assertEquals(3, replayed.size());
        for (var batch : replayed) {
            assertNotNull(batch.batchId);
            assertTrue(batch.batchId.matches("^[A-Za-z0-9._:-]{8,128}$"));
        }
    }

    @Test
    void chunksLargeSnapshotsIntoBatchesOfAtMostFiftyItems(@TempDir Path tempDir) throws Exception {
        var received = new CopyOnWriteArrayList<SkillTelemetryBatchRequest>();
        var latch = new CountDownLatch(2);
        var uploader = new CliSkillTelemetryUploader(batch -> {
            received.add(batch);
            latch.countDown();
        }, tempDir.resolve("q.jsonl"), 16, 10);

        for (int i = 0; i < 65; i++) {
            uploader.recordInvoke(new SkillTelemetryIdentity("skill-" + i, "team/s-" + i, "sha"));
        }
        uploader.flushAsync();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(2, received.size());
        List<Integer> sizes = received.stream().map(b -> b.items.size()).sorted().toList();
        assertEquals(List.of(15, 50), sizes);
    }

    @Test
    void identityResolverReportsOnlyHubLinkedSkillsForMatchingServer(@TempDir Path tempDir) throws Exception {
        var linkedDir = Files.createDirectories(tempDir.resolve("linked"));
        SkillHubMarker.write(linkedDir, SkillHubMarker.Marker.pulled(
                "team/demo", "skill-123", "sha-abc", "https://hub.example.com/"));

        var privateDir = Files.createDirectories(tempDir.resolve("private"));
        var otherServerDir = Files.createDirectories(tempDir.resolve("other"));
        SkillHubMarker.write(otherServerDir, SkillHubMarker.Marker.pulled(
                "team/other", "skill-999", "sha-999", "https://other.example.com"));

        var resolver = new CliSkillIdentityResolver("https://hub.example.com");

        var linkedMeta = SkillMetadata.builder("demo", "desc", linkedDir.resolve("SKILL.md").toString())
                .namespace("team").skillDir(linkedDir.toString()).build();
        var privateMeta = SkillMetadata.builder("priv", "desc", privateDir.resolve("SKILL.md").toString())
                .namespace("local").skillDir(privateDir.toString()).build();
        var otherMeta = SkillMetadata.builder("other", "desc", otherServerDir.resolve("SKILL.md").toString())
                .namespace("team").skillDir(otherServerDir.toString()).build();

        var resolved = resolver.apply(linkedMeta);
        assertNotNull(resolved);
        assertEquals("skill-123", resolved.skillId());
        assertEquals("team/demo", resolved.qualifiedName());
        assertEquals("sha-abc", resolved.packageDigest());

        assertNull(resolver.apply(privateMeta));
        assertNull(resolver.apply(otherMeta));
    }

    private void waitUntilDroppedOfflineCount(CliSkillTelemetryUploader uploader, long expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (uploader.droppedOfflineBatchCount() >= expected) {
                return;
            }
            Thread.sleep(25);
        }
    }
}
