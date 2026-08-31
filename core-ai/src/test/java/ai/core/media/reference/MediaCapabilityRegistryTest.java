package ai.core.media.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Family defaults are what a model gets before any admin override, so a stale row here silently
 * drops references the model would have accepted.
 *
 * @author stephen
 */
class MediaCapabilityRegistryTest {
    @Test
    void geminiOmniTakesVideoReferencesButNoAudio() {
        var caps = MediaCapabilityRegistry.lookup("gemini-omni-1.1-flash");

        assertEquals(3, caps.maxVideos(), "1.1 documents 3 clips of up to 3s each");
        assertEquals(4, caps.maxImages());
        assertEquals(0, caps.maxAudios(), "uploading audio references is documented as unsupported");
        assertFalse(caps.acceptsAudioRef());
    }

    @Test
    void longestPrefixWins() {
        assertEquals(3, MediaCapabilityRegistry.lookup("gemini-omni-flash").maxVideos(), "omni is more specific than gemini-");
        assertEquals(0, MediaCapabilityRegistry.lookup("gemini-2.5-flash").maxVideos(), "plain gemini takes images only");
    }

    @Test
    void unknownModelIsUnconstrainedRatherThanZero() {
        var caps = MediaCapabilityRegistry.lookup("brand-new-model");

        assertNull(caps.maxImages(), "an unknown model must not be trimmed to nothing");
        assertEquals(MediaAddressingSyntax.NONE, caps.addressingSyntaxOrNone());
        assertNull(MediaCapabilityRegistry.lookup(null).maxImages());
    }
}
