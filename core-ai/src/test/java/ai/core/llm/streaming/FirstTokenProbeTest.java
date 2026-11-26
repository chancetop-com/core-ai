package ai.core.llm.streaming;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertNull(probe.firstContentMillis());
        assertEquals(List.of("thinking"), delegate.reasoningChunks);
    }

    @Test
    void firstContentIsMeasuredAfterTheReasoningPhase() {
        var probe = new FirstTokenProbe(new RecordingCallback(), System.nanoTime() - TimeUnit.SECONDS.toNanos(2));

        probe.onReasoningChunk("thinking");
        probe.onChunk("answer");

        assertTrue(probe.firstContentMillis() != null && probe.firstContentMillis() >= 2000);
        assertTrue(probe.ttftMillis() != null && probe.ttftMillis() >= 2000);
    }

    @Test
    void contentChunkIsBothFirstTokenAndFirstContent() {
        var probe = new FirstTokenProbe(new RecordingCallback());

        probe.onChunk("answer");

        assertNotNull(probe.ttftMillis());
        assertNotNull(probe.firstContentMillis());
    }

    @Test
    void resetClearsBothMeasurements() {
        var probe = new FirstTokenProbe(new RecordingCallback());

        probe.onChunk("answer");
        probe.reset();

        assertNull(probe.ttftMillis());
        assertNull(probe.firstContentMillis());
    }

    @Test
    void keepsTheFirstTokenTimeWhenLaterChunksArrive() {
        var probe = new FirstTokenProbe(new RecordingCallback());

        probe.onChunk("a");
        var first = probe.ttftMillis();
        probe.onChunk("b");

        assertNotNull(first);
        assertTrue(probe.ttftMillis() >= first);
    }

    @Test
    void noTokenMeansNoMeasurement() {
        var probe = new FirstTokenProbe(new RecordingCallback());

        probe.onComplete();

        assertNull(probe.ttftMillis());
    }

    @Test
    void notifiesTheFirstTokenSinkOnceWithTheMeasuredLatency() {
        var sinkValues = new ArrayList<Long>();
        var probe = new FirstTokenProbe(new RecordingCallback(), System.nanoTime() - TimeUnit.SECONDS.toNanos(2), sinkValues::add);

        probe.onChunk("a");
        probe.onChunk("b");

        assertEquals(1, sinkValues.size());
        assertEquals(probe.ttftMillis(), sinkValues.getFirst());
        assertTrue(sinkValues.getFirst() >= 2000, "unexpected ttft: " + sinkValues.getFirst());
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
