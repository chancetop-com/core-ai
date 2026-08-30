package ai.core.server.gateway;

import ai.core.media.reference.MediaModality;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Stephen
 */
class GatewayMediaHandleTest {
    @Test
    void roundTripsBothModalities() {
        var image = GatewayMediaHandle.encodeImage("job-1");
        var video = GatewayMediaHandle.encodeVideo("job-1");

        assertTrue(image.startsWith("gateway-media-v1.img."));
        assertTrue(video.startsWith("gateway-media-v1.vid."));
        assertEquals(new GatewayMediaHandle.Handle("job-1", MediaModality.IMAGE), GatewayMediaHandle.decode(image));
        assertEquals(new GatewayMediaHandle.Handle("job-1", MediaModality.VIDEO), GatewayMediaHandle.decode(video));
    }

    @Test
    void keepsDecodingLegacyVideoHandlesAlreadyInFlight() {
        var legacy = "gateway-video-v1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("job-legacy".getBytes(StandardCharsets.UTF_8));

        assertEquals("job-legacy", GatewayVideoHandle.decode(legacy));
        assertEquals(MediaModality.VIDEO, GatewayMediaHandle.decode(legacy).modality());
    }

    @Test
    void rejectsHandlesThisGatewayDidNotMint() {
        assertThrows(BadRequestException.class, () -> GatewayMediaHandle.decode("upstream-video-id"));
        assertThrows(BadRequestException.class, () -> GatewayMediaHandle.decode("gateway-media-v1.aud.abc"));
        assertFalse(GatewayMediaHandle.isHandle("https://example.com/a.png"));
        assertTrue(GatewayMediaHandle.isHandle(GatewayMediaHandle.encodeImage("job-1")));
    }

    @Test
    void refusesToReadAnImageHandleAsAVideoId() {
        var image = GatewayMediaHandle.encodeImage("job-1");

        assertThrows(BadRequestException.class, () -> GatewayVideoHandle.decode(image));
    }
}
