package ai.core.media.reference;

import ai.core.media.domain.MediaReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class MediaReferenceParserTest {
    @Test
    void parsesHandleWithNameAndRole() {
        var references = MediaReferenceParser.parse(
                "[{\"media_id\":\"gateway-media-v1.img.abc\",\"name\":\"char_lin\",\"role\":\"subject\"}]", "input_images");

        assertEquals(1, references.size());
        var reference = references.getFirst();
        assertEquals("gateway-media-v1.img.abc", reference.mediaId());
        assertEquals("char_lin", reference.name());
        assertEquals(MediaReferenceRole.SUBJECT, reference.role());
        // parsing does no I/O: the representation is chosen after routing
        assertNull(reference.url());
        assertNull(reference.b64Json());
    }

    @Test
    void parsesLastShorthand() {
        var references = MediaReferenceParser.parse("[\"last\"]", "input_images");

        assertTrue(references.getFirst().isSymbolic());
        assertEquals(MediaReference.LAST, references.getFirst().mediaId());
    }

    @Test
    void treatsBareHandleStringAsReferenceNotUrl() {
        var references = MediaReferenceParser.parse("[\"gateway-media-v1.vid.abc\"]", "input_references");

        assertEquals("gateway-media-v1.vid.abc", references.getFirst().mediaId());
        assertNull(references.getFirst().url());
    }

    @Test
    void keepsExternalUrlAndDataUrlEscapeHatches() {
        var references = MediaReferenceParser.parse(
                "[\"https://example.com/a.png\",\"data:image/png;base64,AAA\"]", "input_images");

        assertEquals("https://example.com/a.png", references.get(0).url());
        assertEquals("data:image/png;base64,AAA", references.get(1).b64Json());
    }

    @Test
    void rejectsEmptyItemAndUnaddressableName() {
        assertThrows(IllegalArgumentException.class, () -> MediaReferenceParser.parse("[{}]", "input_images"));
        assertThrows(IllegalArgumentException.class,
                () -> MediaReferenceParser.parse("[{\"media_id\":\"x\",\"name\":\"char lin\"}]", "input_images"));
        assertThrows(IllegalArgumentException.class,
                () -> MediaReferenceParser.parse("[{\"media_id\":\"x\",\"role\":\"villain\"}]", "input_images"));
    }
}
