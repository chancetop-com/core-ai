package ai.core.agent.internal;

import ai.core.llm.domain.FunctionCall;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoomLoopDetectorTest {

    private FunctionCall call(String name, String args) {
        return FunctionCall.of("call_1", "function", name, args);
    }

    @Test
    void detectsIdenticalCallsReachingWindowSize() {
        var detector = new DoomLoopDetector(3);
        assertFalse(detector.record(call("read_file", "{\"path\": \"/a.txt\"}")));
        assertFalse(detector.record(call("read_file", "{\"path\": \"/a.txt\"}")));
        assertTrue(detector.record(call("read_file", "{\"path\": \"/a.txt\"}")));
    }

    @Test
    void differentArgumentsDoNotTrigger() {
        var detector = new DoomLoopDetector(3);
        detector.record(call("read_file", "{\"path\": \"/a.txt\"}"));
        detector.record(call("read_file", "{\"path\": \"/b.txt\"}"));
        assertFalse(detector.record(call("read_file", "{\"path\": \"/a.txt\"}")));
    }

    @Test
    void differentToolsDoNotTrigger() {
        var detector = new DoomLoopDetector(3);
        detector.record(call("read_file", "{}"));
        detector.record(call("write_file", "{}"));
        assertFalse(detector.record(call("read_file", "{}")));
    }

    @Test
    void resetClearsHistory() {
        var detector = new DoomLoopDetector(3);
        detector.record(call("read_file", "{}"));
        detector.record(call("read_file", "{}"));
        detector.reset();
        assertFalse(detector.record(call("read_file", "{}")));
        assertFalse(detector.record(call("read_file", "{}")));
        assertTrue(detector.record(call("read_file", "{}")));
    }

    @Test
    void normalizesWhitespace() {
        var detector = new DoomLoopDetector(3);
        detector.record(call("tool", "{\"a\":  1}"));
        detector.record(call("tool", "{\"a\":   1}"));
        // both normalize to {"a": 1} after collapsing whitespace
        assertTrue(detector.record(call("tool", "{\"a\": 1}")));
    }

    @Test
    void nullArgumentsHandled() {
        var detector = new DoomLoopDetector(3);
        detector.record(call("tool", null));
        detector.record(call("tool", null));
        assertTrue(detector.record(call("tool", null)));
    }

    @Test
    void windowSizeOfTwo() {
        var detector = new DoomLoopDetector(2);
        assertFalse(detector.record(call("tool", "{}")));
        assertTrue(detector.record(call("tool", "{}")));
    }

    @Test
    void slidingWindowEvictsCorrectly() {
        var detector = new DoomLoopDetector(3);
        detector.record(call("tool_a", "{}"));
        detector.record(call("tool_b", "{}"));
        assertFalse(detector.record(call("tool_b", "{}")));
        // window was [tool_a, tool_b, tool_b] -> 2 distinct -> false
        // now add tool_b again, evicts tool_a -> [tool_b, tool_b, tool_b]
        assertTrue(detector.record(call("tool_b", "{}")));
    }
}
