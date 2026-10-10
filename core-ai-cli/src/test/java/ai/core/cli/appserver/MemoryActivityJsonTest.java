package ai.core.cli.appserver;

import ai.core.cli.memory.MemoryExtractionReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MemoryActivityJsonTest {

    @Test
    void startedPayloadCarriesRunIdPhaseAndTriggerOnly() {
        var report = new MemoryExtractionReport("run-1", MemoryExtractionReport.Phase.STARTED,
                MemoryExtractionReport.Trigger.EXPLICIT, 0, -1, List.of(), List.of(), null);

        var payload = MemoryActivityJson.payload(report);

        assertEquals("run-1", payload.get("runId").asText());
        assertEquals("started", payload.get("phase").asText());
        assertEquals("explicit", payload.get("trigger").asText());
        assertFalse(payload.has("added"));
        assertFalse(payload.has("note"));
    }

    @Test
    void completedPayloadCarriesExtractedPagesAndNote() {
        var report = new MemoryExtractionReport("run-2", MemoryExtractionReport.Phase.COMPLETED,
                MemoryExtractionReport.Trigger.TURN, 1234, 42,
                List.of(".core-ai/knowledge/reference/x.md"), List.of(".core-ai/knowledge/MEMORY.md"),
                "## [2026-10-10] ingest | probe");

        var payload = MemoryActivityJson.payload(report);

        assertEquals("completed", payload.get("phase").asText());
        assertEquals("turn", payload.get("trigger").asText());
        assertEquals(1234, payload.get("durationMs").asLong());
        assertEquals(42, payload.get("cursor").asInt());
        assertEquals(".core-ai/knowledge/reference/x.md", payload.get("added").get(0).asText());
        assertEquals(".core-ai/knowledge/MEMORY.md", payload.get("updated").get(0).asText());
        assertEquals("## [2026-10-10] ingest | probe", payload.get("note").asText());
    }

    @Test
    void completedPayloadOmitsANullNote() {
        var report = new MemoryExtractionReport("run-3", MemoryExtractionReport.Phase.COMPLETED,
                MemoryExtractionReport.Trigger.IDLE, 10, 0, List.of(), List.of(), null);

        var payload = MemoryActivityJson.payload(report);

        assertFalse(payload.has("note"));
        assertEquals(0, payload.get("added").size());
    }
}
