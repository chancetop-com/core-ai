package ai.core.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shrink policy around the host-provided encoder: what is passed through untouched, what is
 * replaced, and that a failing encoder never breaks the payload it was asked to shrink.
 *
 * @author stephen
 */
class ImageDownscalerTest {
    private static final String ORIGINAL = Base64.getEncoder().encodeToString(new byte[4]);

    @AfterEach
    void clearShrinker() {
        // the registry is process-wide, other tests must see the unshrunk default again
        ImageDownscaler.register(null);
    }

    @Test
    void withoutAShrinkerThePayloadIsUntouched() {
        var result = ImageDownscaler.shrink("QUJD", "image/png");

        assertEquals("QUJD", result.data());
        assertEquals("image/png", result.format());
    }

    @Test
    void smallerPayloadIsReplacedWithJpeg() {
        ImageDownscaler.register(base64 -> Base64.getEncoder().encodeToString(new byte[2]));

        var result = ImageDownscaler.shrink(ORIGINAL, "image/png");

        assertEquals("image/jpeg", result.format());
        assertTrue(result.data().length() < ORIGINAL.length());
    }

    @Test
    void payloadThatWouldNotShrinkIsKeptAsIs() {
        ImageDownscaler.register(base64 -> ORIGINAL + "A");

        var result = ImageDownscaler.shrink(ORIGINAL, "image/png");

        assertEquals(ORIGINAL, result.data());
        assertEquals("image/png", result.format());
    }

    @Test
    void undecodablePayloadIsKeptAsIs() {
        ImageDownscaler.register(base64 -> null);

        var result = ImageDownscaler.shrink(ORIGINAL, "image/png");

        assertEquals(ORIGINAL, result.data());
        assertEquals("image/png", result.format());
    }

    @Test
    void failingShrinkerNeverBreaksTheRequest() {
        ImageDownscaler.register(base64 -> {
            throw new IllegalStateException("no image pipeline");
        });

        var result = ImageDownscaler.shrink(ORIGINAL, "image/png");

        assertEquals(ORIGINAL, result.data());
        assertEquals("image/png", result.format());
    }

    @Test
    void nullAndBlankPayloadsAreSafe() {
        ImageDownscaler.register(base64 -> ORIGINAL);

        assertNull(ImageDownscaler.shrink(null, "image/png").data());
        assertEquals("", ImageDownscaler.shrink("", "image/png").data());
    }
}
