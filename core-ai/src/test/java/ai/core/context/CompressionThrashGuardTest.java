package ai.core.context;

import ai.core.llm.domain.FunctionCall;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class CompressionThrashGuardTest {

    private static FunctionCall read(String path) {
        return FunctionCall.of("call-1", "function", "read_file", "{\"file_path\":\"" + path + "\"}");
    }

    @Test
    void repeatedCallsBeforeAnyCompressionAreNotThrash() {
        var guard = new CompressionThrashGuard(3);
        guard.beginRun();

        for (int i = 0; i < 10; i++) {
            guard.onToolCall(read("a.md"));
        }

        assertDoesNotThrow(() -> guard.check(128000));
        assertEquals(0, guard.redundantCalls());
    }

    @Test
    void sameArgumentsReplayedAfterCompressionTripsTheGuard() {
        var guard = new CompressionThrashGuard(3);
        guard.beginRun();
        guard.onToolCall(read("a.md"));
        guard.onCompression();

        guard.onToolCall(read("a.md"));
        guard.onToolCall(read("a.md"));
        guard.onToolCall(read("a.md"));

        assertEquals(3, guard.redundantCalls());
        var error = assertThrows(CompressionThrashException.class, () -> guard.check(128000));
        assertTrue(error.getMessage().contains("read_file"));
        assertTrue(error.getMessage().contains("128000"));
        assertTrue(error.getMessage().contains("context window"));
    }

    @Test
    void differentArgumentsAreProgressNotThrash() {
        var guard = new CompressionThrashGuard(3);
        guard.beginRun();
        guard.onCompression();

        guard.onToolCall(read("a.md"));
        guard.onToolCall(read("b.md"));
        guard.onToolCall(read("c.md"));

        assertEquals(0, guard.redundantCalls());
        assertDoesNotThrow(() -> guard.check(128000));
    }

    @Test
    void progressBetweenRepeatsBreaksTheEvidence() {
        var guard = new CompressionThrashGuard(3);
        guard.beginRun();
        guard.onToolCall(read("a.md"));
        guard.onCompression();

        guard.onProgress();
        guard.onToolCall(read("a.md"));
        assertEquals(0, guard.redundantCalls(), "the first read after progress is legitimate");

        guard.onToolCall(read("a.md"));
        guard.onToolCall(read("a.md"));
        guard.onToolCall(read("a.md"));
        assertEquals(3, guard.redundantCalls());
        assertThrows(CompressionThrashException.class, () -> guard.check(1_000_000));
    }

    @Test
    void beginRunResetsEvidence() {
        var guard = new CompressionThrashGuard(2);
        guard.beginRun();
        guard.onCompression();
        guard.onToolCall(read("a.md"));
        guard.onToolCall(read("a.md"));
        assertEquals(1, guard.redundantCalls());

        guard.beginRun();

        assertEquals(0, guard.redundantCalls());
        assertEquals(0, guard.compressions());
        assertDoesNotThrow(() -> guard.check(128000));
    }
}
