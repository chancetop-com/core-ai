package ai.core.agent.internal;

import ai.core.llm.domain.FunctionCall;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ToolCallFingerprintTest {

    @Test
    void sameToolAndArgumentsAreEqual() {
        var a = ToolCallFingerprint.of(call("read_file", "{\"path\":\"/a.txt\"}"));
        var b = ToolCallFingerprint.of(call("read_file", "{\"path\":\"/a.txt\"}"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentArgumentsAreNotEqual() {
        var a = ToolCallFingerprint.of(call("read_file", "{\"path\":\"/a.txt\"}"));
        var b = ToolCallFingerprint.of(call("read_file", "{\"path\":\"/b.txt\"}"));
        assertNotEquals(a, b);
    }

    @Test
    void differentToolsAreNotEqual() {
        var a = ToolCallFingerprint.of(call("read_file", "{\"path\":\"/a.txt\"}"));
        var b = ToolCallFingerprint.of(call("edit_file", "{\"path\":\"/a.txt\"}"));
        assertNotEquals(a, b);
    }

    @Test
    void differentCallIdsDoNotAffectEquality() {
        var a = ToolCallFingerprint.of(FunctionCall.of("id_1", "function", "read_file", "{\"path\":\"/a.txt\"}"));
        var b = ToolCallFingerprint.of(FunctionCall.of("id_2", "function", "read_file", "{\"path\":\"/a.txt\"}"));
        assertEquals(a, b);
    }

    @Test
    void nullArgumentsHandled() {
        var a = ToolCallFingerprint.of(call("tool", null));
        var b = ToolCallFingerprint.of(call("tool", null));
        assertEquals(a, b);
    }

    @Test
    void blankArgumentsHandled() {
        var a = ToolCallFingerprint.of(call("tool", "  "));
        var b = ToolCallFingerprint.of(call("tool", ""));
        assertEquals(a, b);
    }

    @Test
    void getToolNameReturnsCorrectValue() {
        var fp = ToolCallFingerprint.of(call("my_tool", "{}"));
        assertEquals("my_tool", fp.getToolName());
    }

    private FunctionCall call(String name, String arguments) {
        return FunctionCall.of("call_1", "function", name, arguments);
    }
}
