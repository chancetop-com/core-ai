package ai.core.cli.appserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class AppServerEngineTest {
    @Test
    void desktopClientMapsToDesktopTraceOrigin() {
        // The desktop declares itself in the initialize handshake; its turns must not trace as "cli".
        assertEquals("desktop", AppServerEngine.traceClientType("core-ai-desktop"));
    }

    @Test
    void unknownOrAbsentClientKeepsCliDefault() {
        assertEquals("cli", AppServerEngine.traceClientType(null));
        assertEquals("cli", AppServerEngine.traceClientType("some-other-client"));
    }
}
