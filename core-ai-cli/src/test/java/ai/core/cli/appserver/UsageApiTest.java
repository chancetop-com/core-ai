package ai.core.cli.appserver;

import ai.core.session.SessionPersistence;
import ai.core.session.transcript.TranscriptRecord;
import ai.core.session.transcript.TranscriptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsageApiTest {
    @TempDir
    Path dir;

    @Test
    void sumsCompletedTurnsFromPersistedTranscripts() {
        writeSession("s-1", 2, 100);
        writeSession("s-2", 1, 50);

        var node = UsageApi.summarize(dir, List.of(
                new SessionPersistence.SessionInfo("s-1", Instant.now()),
                new SessionPersistence.SessionInfo("s-2", Instant.now())), null, 200);

        var totals = node.path("totals");
        assertThat(totals.path("sessions").asInt()).isEqualTo(2);
        assertThat(totals.path("inputTokens").asLong()).isEqualTo(250);
        assertThat(totals.path("outputTokens").asLong()).isEqualTo(125);
        assertThat(totals.path("costUsd").asDouble()).isCloseTo(0.15, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(totals.path("completedTurns").asInt()).isEqualTo(3);
        assertThat(node.path("sessions").size()).isEqualTo(2);
    }

    @Test
    void cutoffSkipsSessionsOlderThanTheRange() {
        writeSession("old", 1, 10);
        var node = UsageApi.summarize(dir,
                List.of(new SessionPersistence.SessionInfo("old", Instant.now().minusSeconds(86_400 * 8))),
                Instant.now().minusSeconds(86_400 * 7), 200);
        assertThat(node.path("totals").path("sessions").asInt()).isZero();
        assertThat(node.path("totals").path("completedTurns").asInt()).isZero();
    }

    private void writeSession(String sessionId, int turns, long inputPerTurn) {
        try (var store = TranscriptStore.open(dir, sessionId)) {
            long seq = 1;
            for (int i = 0; i < turns; i++) {
                var turnId = "t-" + i;
                store.append(record(seq++, "turn_start", turnId));
                store.append(record(seq++, "turn_complete", turnId)
                        .put("input_tokens", inputPerTurn)
                        .put("output_tokens", inputPerTurn / 2)
                        .put("cost_usd", 0.05));
            }
        }
    }

    private TranscriptRecord record(long seq, String kind, String turnId) {
        var record = new TranscriptRecord();
        record.seq = seq;
        record.ts = System.currentTimeMillis();
        record.kind = kind;
        record.turnId = turnId;
        return record;
    }
}
