package ai.core.server.sandbox;

import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SandboxRuntimeProtocolTest {
    @Test
    void deserializesExecuteResponseViaCoreNgJson() {
        var json = "{\"status\":\"cancelled\",\"result\":\"task cancelled\",\"task_id\":\"task-1\",\"duration_ms\":42}";
        var response = JSON.fromJSON(SandboxRuntimeProtocol.ExecuteResponse.class, json);
        assertEquals("cancelled", response.status);
        assertEquals("task cancelled", response.result);
        assertEquals("task-1", response.taskId);
        assertEquals(42L, response.durationMs);
    }
}
