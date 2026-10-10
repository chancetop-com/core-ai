package ai.core.server.trace.web.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class IngestControllerTest {
    @Test
    void desktopClientTypeStampsDesktopSource() {
        assertEquals("desktop", IngestController.traceSource("desktop"));
    }

    @Test
    void absentClientTypeKeepsCliDefault() {
        // Older CLI builds send no clientType at all.
        assertEquals("cli", IngestController.traceSource(null));
        assertEquals("cli", IngestController.traceSource(""));
    }

    @Test
    void unlistedClientTypeCannotClaimOtherSources() {
        assertEquals("cli", IngestController.traceSource("chat"));
        assertEquals("cli", IngestController.traceSource("system"));
    }

    @Test
    void clientTypeIsParsedFromTheWireField() throws Exception {
        // Guards the cross-module wire contract: the CLI sends "clientType" (see HttpTraceUploader.toMap);
        // renaming the field here without a matching change would silently downgrade every desktop trace to cli.
        var request = new ObjectMapper().readValue("{\"clientType\":\"desktop\",\"spans\":[]}", IngestRequest.class);
        assertEquals("desktop", request.clientType);
    }
}
