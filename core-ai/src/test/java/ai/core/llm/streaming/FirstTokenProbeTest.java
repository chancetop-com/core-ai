package ai.core.llm.streaming;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class FirstTokenProbeTest {
    @Test
    void reportsLatencyWhenTheFirstContentChunkArrives() {
        var delegate = new RecordingCallback();
        var probe = new FirstTokenProbe(delegate, System.nanoTime() - TimeUnit.SECONDS.toNanos(2));

        probe.onChunk("hello");

        var ttft = probe.ttftMillis();
        assertTrue(ttft != null && ttft >= 2000 && ttft < 10_000, "unexpected ttft: " + ttft);
        assertEquals(List.of("hello"), delegate.chunks);
    }

    @Test
    void reasoningChunkCountsAsFirstToken() {
        var delegate = new RecordingCallback();
        var probe = new FirstTokenProbe(delegate, System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(150));

        probe.onReasoningChunk("thinking");

        assertTrue(probe.ttftMillis() != null && probe.ttftMillis() >= 150);
        assertEquals(List.of("thinking"), delegate.reasoningChunks);
    }

    @Test
    void keepsTheFirstTokenTimeWhenLaterChunksArrive() {
        var probe = new FirstTokenProbe(new RecordingCallback());

        probe.onChunk("a");
        var first = probe.ttftMillis();
        probe.onChunk("b");

        assertTrue(first != null);
        assertTrue(probe.ttftMillis() >= first);
    }

    @Test
    void noTokenMeansNoMeasurement() {
        var probe = new FirstTokenProbe(new RecordingCallback());

        probe.onComplete();

        assertNull(probe.ttftMillis());
    }

    private static final class RecordingCallback implements StreamingCallback {
        private final List<String> chunks = new ArrayList<>();
        private final List<String> reasoningChunks = new ArrayList<>();

        @Override
        public void onChunk(String chunk) {
            chunks.add(chunk);
        }

        @Override
        public void onReasoningChunk(String chunk) {
            reasoningChunks.add(chunk);
        }
    }
}
