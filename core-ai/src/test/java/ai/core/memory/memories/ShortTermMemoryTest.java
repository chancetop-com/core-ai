package ai.core.memory.memories;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ShortTermMemoryTest {
    private LLMProvider mockLLMProvider;
    private ShortTermMemory memory;

    @BeforeEach
    void setUp() {
        mockLLMProvider = Mockito.mock(LLMProvider.class);
        memory = new ShortTermMemory(100, mockLLMProvider);
    }

    @Test
    void testGetType() {
        assertEquals(MemoryType.SHORT_TERM.getDisplayName(), memory.getType());
    }

    @Test
    void testSaveAndRetrieve() {
        var messages = List.of(
            Message.of(RoleType.USER, "Hello, how are you?"),
            Message.of(RoleType.ASSISTANT, "I am doing great!")
        );

        memory.save(messages);
        var docs = memory.retrieve("query");

        assertEquals(2, docs.size());
        assertEquals("Hello, how are you?", docs.get(0).content);
        assertEquals("I am doing great!", docs.get(1).content);
    }

    @Test
    void testSaveWithDeduplication() {
        var messages = List.of(
            Message.of(RoleType.USER, "Hello"),
            Message.of(RoleType.ASSISTANT, "Hi there"),
            Message.of(RoleType.USER, "Hello")
        );

        memory.save(messages);

        assertEquals(2, memory.getRecentMessageCount());
    }

    @Test
    void testSaveIgnoresNullContent() {
        var msg1 = Message.of(RoleType.USER, "Hello");
        var msg2 = Message.of(RoleType.ASSISTANT, null);
        var msg3 = Message.of(RoleType.USER, "");

        memory.save(List.of(msg1, msg2, msg3));

        assertEquals(1, memory.getRecentMessageCount());
    }

    @Test
    void testClear() {
        memory.save(List.of(Message.of(RoleType.USER, "Test message")));
        assertEquals(1, memory.getRecentMessageCount());

        memory.clear();

        assertEquals(0, memory.getRecentMessageCount());
        assertFalse(memory.hasCompressedSummary());
    }

    @Test
    void testIsEmpty() {
        assertTrue(memory.isEmpty());

        memory.save(List.of(Message.of(RoleType.USER, "Test")));

        assertFalse(memory.isEmpty());
    }

    @Test
    void testCompressionTriggered() {
        var summaryResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "Summary"))),
            new Usage()
        );
        when(mockLLMProvider.completion(any(CompletionRequest.class))).thenReturn(summaryResponse);

        memory = new ShortTermMemory(10, mockLLMProvider);

        memory.save(List.of(
            Message.of(RoleType.USER, "This is a very long message that will definitely exceed the token limit and trigger compression"),
            Message.of(RoleType.ASSISTANT, "Another long message to trigger compression and create summary buffer")
        ));

        assertTrue(memory.hasCompressedSummary());
    }

    @Test
    void testGetTokenLimit() {
        assertEquals(100, memory.getTokenLimit());
    }

    @Test
    void testGetCurrentTokenCount() {
        memory.save(List.of(Message.of(RoleType.USER, "Test")));
        assertTrue(memory.getCurrentTokenCount() > 0);
    }

    @Test
    void testRetrieveReturnsAllContent() {
        memory.save(List.of(
            Message.of(RoleType.USER, "Message 1"),
            Message.of(RoleType.ASSISTANT, "Message 2")
        ));

        var docs = memory.retrieve("query");

        assertEquals(2, docs.size());
    }

    @Test
    void testDefaultTokenLimit() {
        var defaultMemory = new ShortTermMemory(mockLLMProvider);
        assertEquals(5000, defaultMemory.getTokenLimit());
    }
}
