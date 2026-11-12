package ai.core.server.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author Xander
 */
class GatewayModelCatalogTest {
    @Test
    void backfillCapabilitiesFromSeedWhenDiscoveryLacksThem() {
        var enriched = GatewayModelCatalog.enrich(metadata("azure/gpt-5-mini", null, null));

        assertEquals(Boolean.TRUE, enriched.supportsVision());
        assertEquals(Boolean.TRUE, enriched.supportsFile());
    }

    @Test
    void backfillKnownTextOnlyModelAsNotVisionCapable() {
        var enriched = GatewayModelCatalog.enrich(metadata("deepseek/deepseek-v4-pro", null, null));

        assertEquals(Boolean.FALSE, enriched.supportsVision());
    }

    @Test
    void providerDeclaredCapabilityWinsOverSeed() {
        var enriched = GatewayModelCatalog.enrich(metadata("deepseek/deepseek-v4-pro", Boolean.TRUE, null));

        assertEquals(Boolean.TRUE, enriched.supportsVision());
    }

    @Test
    void infersAudioTranscriptionForWhisperAndTranscribeModels() {
        assertEquals(java.util.List.of(GatewayModelService.ENDPOINT_AUDIO_TRANSCRIPTION),
                GatewayModelCatalog.enrich(metadata("whisper-1", null, null)).endpointTypes());
        assertEquals(java.util.List.of(GatewayModelService.ENDPOINT_AUDIO_TRANSCRIPTION),
                GatewayModelCatalog.enrich(metadata("gpt-4o-transcribe", null, null)).endpointTypes());
        assertEquals(java.util.List.of(),
                GatewayModelCatalog.enrich(metadata("tts-1", null, null)).endpointTypes());
    }

    @Test
    void unknownModelKeepsCapabilitiesNull() {
        var enriched = GatewayModelCatalog.enrich(metadata("totally-unknown-model", null, null));

        assertNull(enriched.supportsVision());
        assertNull(enriched.supportsFile());
    }

    private GatewayModelMetadata metadata(String id, Boolean supportsVision, Boolean supportsFile) {
        return new GatewayModelMetadata(id, null, null, null, null, null, supportsVision, supportsFile, null, null);
    }
}
