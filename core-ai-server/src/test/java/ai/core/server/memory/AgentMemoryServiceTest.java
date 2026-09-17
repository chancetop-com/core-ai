package ai.core.server.memory;

import ai.core.server.domain.ChatMessage;
import ai.core.server.memory.experiment.MemoryLayer;
import ai.core.server.trace.domain.Trace;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryServiceTest {
    private final MongoCollection<AgentMemory> memoryCollection = memoryCollection();
    private final MongoCollection<Trace> traceCollection = traceCollection();
    private final MongoCollection<ChatMessage> chatMessageCollection = chatMessageCollection();
    private final AgentMemoryService service = service();

    @Test
    void rememberKnowledgeStoresTheStatementAsKnowledge() {
        when(memoryCollection.find(any(Query.class))).thenReturn(List.of());

        var result = service.rememberKnowledge("agent-1", "  The user prefers metric units  ", "USER_PREFERENCE");

        assertEquals(RememberResult.Status.CREATED, result.status());
        var stored = result.memory();
        assertEquals("agent-1", stored.agentId);
        assertEquals("USER_PREFERENCE", stored.type);
        assertEquals(MemoryLayer.KNOWLEDGE, stored.layer);
        assertEquals("The user prefers metric units", stored.content);
        verify(memoryCollection).insert(stored);
    }

    @Test
    void unknownTypeFallsBackToDomainKnowledge() {
        when(memoryCollection.find(any(Query.class))).thenReturn(List.of());

        var result = service.rememberKnowledge("agent-1", "the tenant is acme-prod", "SOMETHING_ELSE");

        assertEquals("DOMAIN_KNOWLEDGE", result.memory().type);
    }

    @Test
    void repeatedStatementIsNotStoredTwice() {
        var existing = knowledge("agent-1", "The user prefers metric units");
        when(memoryCollection.find(any(Query.class))).thenReturn(List.of(existing));

        var result = service.rememberKnowledge("agent-1", "  the user   prefers metric units ", "USER_PREFERENCE");

        assertEquals(RememberResult.Status.ALREADY_EXISTS, result.status());
        assertSame(existing, result.memory());
        verify(memoryCollection, never()).insert(any());
    }

    @Test
    void findIsScopedToTheOwningAgent() {
        var memory = knowledge("agent-1", "the user prefers metric units");
        when(memoryCollection.get("memory-1")).thenReturn(Optional.of(memory));

        assertSame(memory, service.find("agent-1", "memory-1"));
        assertNull(service.find("agent-2", "memory-1"));
        assertNull(service.find("agent-1", " "));
    }

    @Test
    void sourceTracesAreResolvedByStartTime() {
        var later = trace("trace-2", ZonedDateTime.parse("2026-09-17T10:00:00Z"));
        var earlier = trace("trace-1", ZonedDateTime.parse("2026-09-17T09:00:00Z"));
        when(traceCollection.find(any(Query.class))).thenReturn(List.of(later, earlier));

        var traces = service.sourceTraces(List.of("trace-1", "trace-2", "trace-archived"));

        assertEquals(List.of("trace-1", "trace-2"), traces.stream().map(t -> t.traceId).toList());
    }

    @Test
    void sourceTracesWithoutIdsDoesNotQueryMongo() {
        assertTrue(service.sourceTraces(null).isEmpty());
        assertTrue(service.sourceTraces(List.of()).isEmpty());
        verify(traceCollection, never()).find(any(Query.class));
    }

    @Test
    void evidenceKeepsTheConversationAndToolCalls() {
        var trace = trace("trace-1", ZonedDateTime.parse("2026-09-17T09:00:00Z"));
        trace.completedAt = ZonedDateTime.parse("2026-09-17T09:05:00Z");
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of(
                message("user", "deploy the tenant", null),
                message("agent", "deployed it", List.of(toolCall("bash", "{\"command\":\"kubectl apply\"}", "ok")))
        ));

        var evidence = service.renderEvidence(trace);

        assertTrue(evidence.contains("User: deploy the tenant"));
        assertTrue(evidence.contains("Assistant: deployed it"));
        assertTrue(evidence.contains("[tool] bash (done)"));
        assertTrue(evidence.contains("kubectl apply"));
    }

    @Test
    void evidenceOmitsLeadingMessagesAndTruncatesLongResults() {
        var trace = trace("trace-1", ZonedDateTime.parse("2026-09-17T09:00:00Z"));
        var messages = new ArrayList<ChatMessage>();
        for (int i = 0; i < 15; i++) {
            messages.add(message("user", "message-" + i, null));
        }
        messages.add(message("agent", "last turn", List.of(toolCall("bash", "{}", "x".repeat(600)))));
        when(chatMessageCollection.find(any(Query.class))).thenReturn(messages);

        var evidence = service.renderEvidence(trace);

        assertTrue(evidence.contains("earlier messages omitted"));
        assertFalse(evidence.contains("User: message-0"));
        assertTrue(evidence.contains("Assistant: last turn"));
        assertTrue(evidence.contains("..."));
        assertTrue(evidence.length() < 20000, "evidence must stay bounded");
    }

    @Test
    void evidenceWithoutRetainedConversationSaysSo() {
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of());

        var evidence = service.renderEvidence(trace("trace-1", ZonedDateTime.now()));

        assertTrue(evidence.contains("no conversation retained"));
    }

    private AgentMemory knowledge(String agentId, String content) {
        var memory = new AgentMemory();
        memory.id = "memory-1";
        memory.agentId = agentId;
        memory.type = KnowledgeType.DOMAIN_KNOWLEDGE.name();
        memory.layer = MemoryLayer.KNOWLEDGE;
        memory.content = content;
        memory.createdAt = ZonedDateTime.now();
        return memory;
    }

    private Trace trace(String traceId, ZonedDateTime startedAt) {
        var trace = new Trace();
        trace.traceId = traceId;
        trace.sessionId = "session-1";
        trace.userId = "user-1";
        trace.startedAt = startedAt;
        return trace;
    }

    private ChatMessage message(String role, String content, List<ChatMessage.ToolCallRecord> tools) {
        var message = new ChatMessage();
        message.role = role;
        message.content = content;
        message.tools = tools;
        message.createdAt = ZonedDateTime.now();
        return message;
    }

    private ChatMessage.ToolCallRecord toolCall(String name, String arguments, String result) {
        var tool = new ChatMessage.ToolCallRecord();
        tool.name = name;
        tool.status = "done";
        tool.arguments = arguments;
        tool.result = result;
        return tool;
    }

    private AgentMemoryService service() {
        var service = new AgentMemoryService();
        service.memoryCollection = memoryCollection;
        service.traceCollection = traceCollection;
        service.chatMessageCollection = chatMessageCollection;
        return service;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<AgentMemory> memoryCollection() {
        return mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Trace> traceCollection() {
        return mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<ChatMessage> chatMessageCollection() {
        return mock(MongoCollection.class);
    }
}
