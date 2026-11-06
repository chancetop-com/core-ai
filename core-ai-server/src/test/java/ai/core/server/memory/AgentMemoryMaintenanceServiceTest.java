package ai.core.server.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryMaintenanceServiceTest {

    private final AgentMemoryService memoryService = mock(AgentMemoryService.class);
    private final AgentMemoryMaintenanceService service = service();

    @Test
    void runsTrajectoryCleanupAndExactDedupeForEveryAgent() {
        when(memoryService.findAgentIdsWithMemories()).thenReturn(List.of("agent-1", "agent-2"));
        when(memoryService.cleanupOldTrajectories("agent-1", 90)).thenReturn(2);
        when(memoryService.cleanupOldTrajectories("agent-2", 90)).thenReturn(3);
        when(memoryService.dedupeExactDuplicates("agent-1")).thenReturn(1);
        when(memoryService.dedupeExactDuplicates("agent-2")).thenReturn(0);

        var report = service.run(90);

        assertEquals(2, report.agents());
        assertEquals(5, report.removedTrajectories());
        assertEquals(1, report.removedDuplicates());
        verify(memoryService).cleanupOldTrajectories("agent-1", 90);
        verify(memoryService).cleanupOldTrajectories("agent-2", 90);
        verify(memoryService).dedupeExactDuplicates("agent-1");
        verify(memoryService).dedupeExactDuplicates("agent-2");
    }

    private AgentMemoryMaintenanceService service() {
        var service = new AgentMemoryMaintenanceService();
        service.agentMemoryService = memoryService;
        return service;
    }
}
