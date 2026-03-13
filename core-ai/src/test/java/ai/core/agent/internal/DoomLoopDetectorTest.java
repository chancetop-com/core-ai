package ai.core.agent.internal;

import ai.core.llm.domain.FunctionCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoomLoopDetectorTest {
    private DoomLoopDetector detector;

    @BeforeEach
    void setUp() {
        detector = new DoomLoopDetector(6, 3);
    }

    @Test
    void noDetectionWhenBelowThreshold() {
        detector.record(call("read_file", "{\"path\":\"/a.txt\"}"));
        detector.record(call("read_file", "{\"path\":\"/a.txt\"}"));

        assertFalse(detector.detect().isPresent());
    }

    @Test
    void detectConsecutiveRepeat() {
        detector.record(call("edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"));
        detector.record(call("edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"));
        detector.record(call("edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"));

        var result = detector.detect();
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("doom loop"));
        assertTrue(result.get().contains("edit_file"));
        assertTrue(result.get().contains("3 times consecutively"));
    }

    @Test
    void noFalsePositiveWithDifferentArguments() {
        detector.record(call("read_file", "{\"path\":\"/a.txt\"}"));
        detector.record(call("read_file", "{\"path\":\"/b.txt\"}"));
        detector.record(call("read_file", "{\"path\":\"/c.txt\"}"));

        assertFalse(detector.detect().isPresent());
    }

    @Test
    void noFalsePositiveWithDifferentTools() {
        detector.record(call("read_file", "{\"path\":\"/a.txt\"}"));
        detector.record(call("edit_file", "{\"path\":\"/a.txt\"}"));
        detector.record(call("write_file", "{\"path\":\"/a.txt\"}"));

        assertFalse(detector.detect().isPresent());
    }

    @Test
    void detectCyclicPattern() {
        detector.record(call("read_file", "{\"path\":\"/a.txt\"}"));
        detector.record(call("edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"));
        detector.record(call("read_file", "{\"path\":\"/a.txt\"}"));
        detector.record(call("edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"));

        var result = detector.detect();
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("cyclic doom loop"));
        assertTrue(result.get().contains("pattern of length 2"));
    }

    @Test
    void resetClearsHistory() {
        detector.record(call("edit_file", "{\"path\":\"/a.txt\"}"));
        detector.record(call("edit_file", "{\"path\":\"/a.txt\"}"));
        detector.record(call("edit_file", "{\"path\":\"/a.txt\"}"));
        assertTrue(detector.detect().isPresent());

        detector.reset();
        assertEquals(0, detector.size());
        assertFalse(detector.detect().isPresent());
    }

    @Test
    void windowSizeLimitsHistory() {
        var smallDetector = new DoomLoopDetector(3, 3);

        smallDetector.record(call("edit_file", "{\"a\":1}"));
        smallDetector.record(call("read_file", "{\"b\":2}"));
        smallDetector.record(call("edit_file", "{\"a\":1}"));
        // window is full (3), oldest evicted
        smallDetector.record(call("edit_file", "{\"a\":1}"));

        // window now: [read_file, edit_file, edit_file] - only 2 consecutive, not 3
        assertFalse(smallDetector.detect().isPresent());
    }

    @Test
    void recoveryAfterDoomLoop() {
        detector.record(call("edit_file", "{\"path\":\"/a.txt\"}"));
        detector.record(call("edit_file", "{\"path\":\"/a.txt\"}"));
        detector.record(call("edit_file", "{\"path\":\"/a.txt\"}"));
        assertTrue(detector.detect().isPresent());

        // agent changes strategy
        detector.record(call("read_file", "{\"path\":\"/b.txt\"}"));
        assertFalse(detector.detect().isPresent());
    }

    @Test
    void emptyDetectorReturnsEmpty() {
        assertFalse(detector.detect().isPresent());
    }

    private FunctionCall call(String name, String arguments) {
        return FunctionCall.of("call_" + System.nanoTime(), "function", name, arguments);
    }
}
