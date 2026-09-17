package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtractMemoryNowToolTest {
    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final ExtractMemoryNowTool tool = new ExtractMemoryNowTool("assistant:user-1", agentMemoryService);
    private final ExecutionContext context = ExecutionContext.builder().sessionId("session-1").userId("user-1").build();

    @Test
    void requiresFocus() {
        var result = tool.execute("{}", context);

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("focus is required"));
    }

    @Test
    void rejectsOverlongFocus() {
        var result = tool.execute("{\"focus\":\"" + "x".repeat(1001) + "\"}", context);

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("too long"));
    }

    @Test
    void directExecutionIsRejected() {
        var result = tool.execute("{\"focus\":\"the tenant is acme-prod\"}");

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("requires ExecutionContext"));
    }

    @Test
    void storesTheStatementUnderTheRequestedType() {
        var stored = memory("the user prefers metric units", "USER_PREFERENCE");
        when(agentMemoryService.rememberKnowledge("assistant:user-1", "the user prefers metric units", "USER_PREFERENCE"))
                .thenReturn(RememberResult.created(stored));

        var result = tool.execute("{\"focus\":\"the user prefers metric units\",\"type\":\"USER_PREFERENCE\"}", context);

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getResult().contains("Remembered as USER_PREFERENCE"));
        assertTrue(result.getResult().contains("available from your next session"));
    }

    @Test
    void unknownTypeIsStoredAsDomainKnowledge() {
        var stored = memory("the tenant is acme-prod", "DOMAIN_KNOWLEDGE");
        when(agentMemoryService.rememberKnowledge(any(), any(), any())).thenReturn(RememberResult.created(stored));

        var result = tool.execute("{\"focus\":\"the tenant is acme-prod\",\"type\":\"NONSENSE\"}", context);

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        verify(agentMemoryService).rememberKnowledge("assistant:user-1", "the tenant is acme-prod", "DOMAIN_KNOWLEDGE");
    }

    @Test
    void repeatedStatementReportsNoChange() {
        var existing = memory("the user prefers metric units", "USER_PREFERENCE");
        when(agentMemoryService.rememberKnowledge(any(), any(), any())).thenReturn(RememberResult.alreadyExists(existing));

        var result = tool.execute("{\"focus\":\"the user prefers metric units\"}", context);

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getResult().contains("Already remembered"));
    }

    private AgentMemory memory(String content, String type) {
        var memory = new AgentMemory();
        memory.id = "memory-1";
        memory.agentId = "assistant:user-1";
        memory.type = type;
        memory.content = content;
        return memory;
    }
}
