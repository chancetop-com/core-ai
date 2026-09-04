package ai.core.cli.hub;

import ai.core.cli.http.RemoteApiException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HubExitCodesTest {
    @Test
    void httpStatusMapsToStableExitCodes() {
        assertEquals(HubExitCodes.UNAUTHENTICATED, HubExitCodes.forException(new RemoteApiException(401, "no")));
        assertEquals(HubExitCodes.FORBIDDEN, HubExitCodes.forException(new RemoteApiException(403, "no")));
        assertEquals(HubExitCodes.NOT_FOUND, HubExitCodes.forException(new RemoteApiException(404, "no")));
        assertEquals(HubExitCodes.TIMEOUT, HubExitCodes.forException(new RemoteApiException(504, "no")));
        assertEquals(HubExitCodes.TOOL_ERROR, HubExitCodes.forException(new RemoteApiException(500, "no")));
        assertEquals(HubExitCodes.TOOL_ERROR, HubExitCodes.forException(new RemoteApiException(502, "no")));
        assertEquals(HubExitCodes.TOOL_ERROR, HubExitCodes.forException(new RemoteApiException(503, "no")));
    }

    @Test
    void networkTimeoutMapsToTimeoutExitCode() {
        var error = new IllegalStateException("API request failed", new HttpTimeoutException("timed out"));
        assertEquals(HubExitCodes.TIMEOUT, HubExitCodes.forException(error));
    }

    @Test
    void otherNetworkFailuresMapToToolError() {
        var error = new IllegalStateException("API request failed", new ConnectException("refused"));
        assertEquals(HubExitCodes.TOOL_ERROR, HubExitCodes.forException(error));
        assertEquals(HubExitCodes.TOOL_ERROR, HubExitCodes.forException(new IOException("boom")));
    }
}
