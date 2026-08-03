package ai.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the litellm-seed backed modality registry, including the model name
 * normalization that resolves routing-convention names like azure/responses/x.
 *
 * @author Xander
 */
class SeedModelModalityRegistryTest {
    private final SeedModelModalityRegistry registry = SeedModelModalityRegistry.INSTANCE;

    @Test
    void textIsAlwaysSupported() {
        assertEquals(ModalitySupport.SUPPORTED, registry.supports("totally-unknown-model", InputModality.TEXT));
    }

    @Test
    void knownTextOnlyModelIsUnsupportedForImage() {
        assertEquals(ModalitySupport.UNSUPPORTED, registry.supports("deepseek/deepseek-v4-flash", InputModality.IMAGE));
    }

    @Test
    void knownVisionModelIsSupportedForImage() {
        assertEquals(ModalitySupport.SUPPORTED, registry.supports("azure/gpt-5-mini", InputModality.IMAGE));
    }

    @Test
    void responsesRoutingSegmentIsStrippedDuringLookup() {
        assertEquals(ModalitySupport.SUPPORTED, registry.supports("azure/responses/gpt-5-mini", InputModality.IMAGE));
    }

    @Test
    void unknownModelIsUnknownForImage() {
        assertEquals(ModalitySupport.UNKNOWN, registry.supports("totally-unknown-model", InputModality.IMAGE));
    }

    @Test
    void pdfCapableModelIsSupportedForFile() {
        assertEquals(ModalitySupport.SUPPORTED, registry.supports("azure/gpt-5-mini", InputModality.FILE));
    }

    @Test
    void knownModelWithoutPdfFlagIsUnsupportedForFile() {
        assertEquals(ModalitySupport.UNSUPPORTED, registry.supports("deepseek/deepseek-v4-flash", InputModality.FILE));
    }

    @Test
    void knownModelWithoutVideoFlagIsUnsupportedForVideo() {
        assertEquals(ModalitySupport.UNSUPPORTED, registry.supports("azure/gpt-5-mini", InputModality.VIDEO));
    }
}
