package ai.core.server.gateway;

import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GatewayVideoHandleTest {
    @Test
    void roundTripsMediaJobId() {
        var id = GatewayVideoHandle.encode("media-job-1");

        var value = GatewayVideoHandle.decode(id);

        assertEquals("media-job-1", value);
    }

    @Test
    void rejectsNonGatewayVideoIds() {
        assertThrows(BadRequestException.class, () -> GatewayVideoHandle.decode("upstream-video-id"));
    }
}
