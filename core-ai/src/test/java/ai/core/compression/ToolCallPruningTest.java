package ai.core.compression;

import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * @author stephen
 */
class ToolCallPruningTest {
    private ToolCallPruning pruning;

    @BeforeEach
    void setUp() {
        pruning = new ToolCallPruning(2, Set.of());
    }

    @Test
    void pruneSingleDigestedSegment() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.SYSTEM, "system"),
                Message.of(RoleType.USER, "read file"),
                assistantWithToolCall("call_1", "read_file"),
                toolResult("call_1", "file content..."),
                Message.of(RoleType.ASSISTANT, "The file contains...")
        ));

        // keepRecentSegments=0 to force pruning
        var p = new ToolCallPruning(0, Set.of());
        var result = p.prune(messages);

        assertNotSame(messages, result);
        assertEquals(3, result.size());
        assertEquals(RoleType.SYSTEM, result.get(0).role);
        assertEquals(RoleType.USER, result.get(1).role);
        assertEquals(RoleType.ASSISTANT, result.get(2).role);
        assertEquals("The file contains...", result.get(2).getTextContent());
    }

    @Test
    void pruneChainedToolCallsAsOneSegment() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "deploy"),
                assistantWithToolCall("call_1", "check_status"),
                toolResult("call_1", "running"),
                assistantWithToolCall("call_2", "build"),
                toolResult("call_2", "image:2.0"),
                assistantWithToolCall("call_3", "deploy"),
                toolResult("call_3", "success"),
                Message.of(RoleType.ASSISTANT, "Deployment complete!")
        ));

        var p = new ToolCallPruning(0, Set.of());
        var result = p.prune(messages);

        assertNotSame(messages, result);
        assertEquals(2, result.size());
        assertEquals(RoleType.USER, result.get(0).role);
        assertEquals("Deployment complete!", result.get(1).getTextContent());
    }

    @Test
    void pruneParallelToolCallsSegment() {
        FunctionCall call1 = FunctionCall.of("call_1", "function", "getWeather", "{\"city\":\"Beijing\"}");
        FunctionCall call2 = FunctionCall.of("call_2", "function", "getWeather", "{\"city\":\"Shanghai\"}");
        Message parallelAssistant = Message.of(RoleType.ASSISTANT, null, null, null, List.of(call1, call2));

        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "compare weather"),
                parallelAssistant,
                toolResult("call_1", "Beijing: 25C"),
                toolResult("call_2", "Shanghai: 30C"),
                Message.of(RoleType.ASSISTANT, "Beijing 25C, Shanghai 30C")
        ));

        var p = new ToolCallPruning(0, Set.of());
        var result = p.prune(messages);

        assertNotSame(messages, result);
        assertEquals(2, result.size());
        assertEquals("compare weather", result.get(0).getTextContent());
        assertEquals("Beijing 25C, Shanghai 30C", result.get(1).getTextContent());
    }

    @Test
    void keepRecentSegments() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "msg1"),
                assistantWithToolCall("c1", "tool1"),
                toolResult("c1", "r1"),
                Message.of(RoleType.ASSISTANT, "digest1"),    // segment A digested

                Message.of(RoleType.USER, "msg2"),
                assistantWithToolCall("c2", "tool2"),
                toolResult("c2", "r2"),
                Message.of(RoleType.ASSISTANT, "digest2"),    // segment B digested

                Message.of(RoleType.USER, "msg3"),
                assistantWithToolCall("c3", "tool3"),
                toolResult("c3", "r3"),
                Message.of(RoleType.ASSISTANT, "digest3")     // segment C digested
        ));

        // keepRecentSegments=2: B and C kept, A pruned
        var result = pruning.prune(messages);

        assertNotSame(messages, result);
        // Original: 12 messages, remove segment A (2 messages: index 1,2) -> 10
        assertEquals(10, result.size());
        // msg1 at 0, digest1 at 1 (segment A's digest preserved)
        assertEquals("msg1", result.get(0).getTextContent());
        assertEquals("digest1", result.get(1).getTextContent());
        // Segment B still intact starting at index 3
        assertEquals("msg2", result.get(2).getTextContent());
        assertEquals("tool2", result.get(3).toolCalls.getFirst().function.name);
    }

    @Test
    void keepUndigestedSegmentAtEnd() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "query"),
                assistantWithToolCall("c1", "search"),
                toolResult("c1", "results")
                // no ASSISTANT(content) follows -> not digested
        ));

        var result = pruning.prune(messages);

        assertSame(messages, result);
    }

    @Test
    void neverPruneMemoryCompressTool() {
        var messages = new ArrayList<>(List.of(
                assistantWithToolCall("mc1", "memory_compress"),
                toolResult("mc1", "[Previous Conversation Summary]..."),
                Message.of(RoleType.USER, "hello"),
                Message.of(RoleType.ASSISTANT, "hi")
        ));

        var p = new ToolCallPruning(0, Set.of());
        var result = p.prune(messages);

        assertSame(messages, result);
    }

    @Test
    void keepSegmentWithExcludedTool() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "search"),
                assistantWithToolCall("c1", "rag_search"),
                toolResult("c1", "search results"),
                Message.of(RoleType.ASSISTANT, "Found results")
        ));

        var p = new ToolCallPruning(0, Set.of("rag_search"));
        var result = p.prune(messages);

        assertSame(messages, result);
    }

    @Test
    void excludedToolInChainPreservesEntireSegment() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "analyze"),
                assistantWithToolCall("c1", "read_file"),
                toolResult("c1", "content"),
                assistantWithToolCall("c2", "rag_search"),
                toolResult("c2", "search results"),
                Message.of(RoleType.ASSISTANT, "Analysis complete")
        ));

        var p = new ToolCallPruning(0, Set.of("rag_search"));
        var result = p.prune(messages);

        // entire segment kept because rag_search is in it
        assertSame(messages, result);
    }

    @Test
    void systemMessageNeverAffected() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.SYSTEM, "You are helpful"),
                Message.of(RoleType.USER, "read"),
                assistantWithToolCall("c1", "read_file"),
                toolResult("c1", "data"),
                Message.of(RoleType.ASSISTANT, "Here is the data")
        ));

        var p = new ToolCallPruning(0, Set.of());
        var result = p.prune(messages);

        assertNotSame(messages, result);
        assertEquals(RoleType.SYSTEM, result.get(0).role);
        assertEquals("You are helpful", result.get(0).getTextContent());
    }

    @Test
    void returnSameReferenceOnNullMessages() {
        var result = pruning.prune(null);
        assertSame(null, result);
    }

    @Test
    void returnSameReferenceOnSmallMessages() {
        var messages = List.of(
                Message.of(RoleType.USER, "hi"),
                Message.of(RoleType.ASSISTANT, "hello")
        );
        var result = pruning.prune(messages);
        assertSame(messages, result);
    }

    @Test
    void returnSameReferenceWhenNoToolCalls() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.SYSTEM, "system"),
                Message.of(RoleType.USER, "hello"),
                Message.of(RoleType.ASSISTANT, "hi"),
                Message.of(RoleType.USER, "how are you"),
                Message.of(RoleType.ASSISTANT, "fine")
        ));

        var result = pruning.prune(messages);
        assertSame(messages, result);
    }

    @Test
    void complexMixedConversation() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.SYSTEM, "system"),

                // Segment A: single tool call
                Message.of(RoleType.USER, "msg1"),
                assistantWithToolCall("c1", "tool1"),
                toolResult("c1", "result1"),
                Message.of(RoleType.ASSISTANT, "digest A"),

                // Segment B: chain tool calls
                Message.of(RoleType.USER, "msg2"),
                assistantWithToolCall("c2", "tool2"),
                toolResult("c2", "result2"),
                assistantWithToolCall("c3", "tool3"),
                toolResult("c3", "result3"),
                Message.of(RoleType.ASSISTANT, "digest B"),

                // Segment C: single tool call
                Message.of(RoleType.USER, "msg3"),
                assistantWithToolCall("c4", "tool4"),
                toolResult("c4", "result4"),
                Message.of(RoleType.ASSISTANT, "digest C"),

                // Segment D: undigested at end
                Message.of(RoleType.USER, "msg4"),
                assistantWithToolCall("c5", "tool5"),
                toolResult("c5", "result5")
        ));

        // keepRecentSegments=2: keep C and B, prune A; D is undigested so always kept
        var result = pruning.prune(messages);

        assertNotSame(messages, result);
        // Original 18 messages, remove segment A (2 messages: c1 assistant + c1 tool) -> 16
        assertEquals(16, result.size());

        // Verify structure: SYSTEM, USER msg1, digest A, USER msg2, segment B..., USER msg3, segment C..., USER msg4, segment D
        assertEquals(RoleType.SYSTEM, result.get(0).role);
        assertEquals("msg1", result.get(1).getTextContent());
        assertEquals("digest A", result.get(2).getTextContent());
        assertEquals("msg2", result.get(3).getTextContent());
        // Segment B intact
        assertEquals("tool2", result.get(4).toolCalls.getFirst().function.name);
    }

    @Test
    void segmentIdentification() {
        var messages = List.of(
                Message.of(RoleType.SYSTEM, "system"),          // 0
                Message.of(RoleType.USER, "hello"),             // 1
                assistantWithToolCall("c1", "t1"),              // 2
                toolResult("c1", "r1"),                         // 3
                Message.of(RoleType.ASSISTANT, "digest"),       // 4
                Message.of(RoleType.USER, "next"),              // 5
                assistantWithToolCall("c2", "t2"),              // 6
                toolResult("c2", "r2"),                         // 7
                assistantWithToolCall("c3", "t3"),              // 8
                toolResult("c3", "r3")                          // 9
        );

        var segments = pruning.identifySegments(messages);

        assertEquals(2, segments.size());
        assertEquals(2, segments.get(0).startIndex());
        assertEquals(4, segments.get(0).endIndex());
        assertEquals(6, segments.get(1).startIndex());
        assertEquals(10, segments.get(1).endIndex());
    }

    @Test
    void directReturnToolCreatesSegmentBoundary() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "directTool"),
                toolResult("c1", "result"),
                Message.of(RoleType.ASSISTANT, "direct return content"),  // injected by handleFunc
                assistantWithToolCall("c2", "anotherTool"),
                toolResult("c2", "result2"),
                Message.of(RoleType.ASSISTANT, "final answer")
        ));

        var p = new ToolCallPruning(0, Set.of());
        var result = p.prune(messages);

        // Both segments digested and pruned
        assertNotSame(messages, result);
        assertEquals(3, result.size());
        assertEquals("do something", result.get(0).getTextContent());
        assertEquals("direct return content", result.get(1).getTextContent());
        assertEquals("final answer", result.get(2).getTextContent());
    }

    @Test
    void memoryCompressInChainPreservesEntireSegment() {
        var messages = new ArrayList<>(List.of(
                assistantWithToolCall("mc", "memory_compress"),
                toolResult("mc", "summary"),
                Message.of(RoleType.USER, "question"),
                assistantWithToolCall("c1", "search"),
                toolResult("c1", "data"),
                Message.of(RoleType.ASSISTANT, "answer")
        ));

        // memory_compress segment is [0,2), search segment is [3,5)
        var p = new ToolCallPruning(0, Set.of());
        var result = p.prune(messages);

        // memory_compress segment kept, search segment pruned
        assertNotSame(messages, result);
        assertEquals(4, result.size());
        assertEquals("memory_compress", result.get(0).toolCalls.getFirst().function.name);
        assertEquals(RoleType.TOOL, result.get(1).role);
        assertEquals("question", result.get(2).getTextContent());
        assertEquals("answer", result.get(3).getTextContent());
    }

    @Test
    void defaultConfigValues() {
        var config = ToolCallPruning.Config.defaultConfig();
        assertEquals(2, config.keepRecentSegments());
        assertEquals(Set.of(), config.excludeToolNames());
    }

    // helper methods

    private Message assistantWithToolCall(String callId, String toolName) {
        FunctionCall call = FunctionCall.of(callId, "function", toolName, "{}");
        return Message.of(RoleType.ASSISTANT, null, null, null, List.of(call));
    }

    private Message toolResult(String callId, String content) {
        return Message.of(RoleType.TOOL, content, null, callId, null);
    }
}
