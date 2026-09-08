package ai.core.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * @author stephen
 */
class SimilaritySearchRequestTest {
    @Test
    @SuppressWarnings("deprecation")
    void effectiveOutputFieldsPrefersExplicitOutputFields() {
        var request = SimilaritySearchRequest.builder()
                .outputFields(List.of("media_library_id", "merchant_id"))
                .extraFields(List.of("legacy"))
                .queryField("query")
                .build();
        assertEquals(List.of("media_library_id", "merchant_id"), request.effectiveOutputFields());
    }

    @Test
    @SuppressWarnings("deprecation")
    void effectiveOutputFieldsMergesLegacyFields() {
        var request = SimilaritySearchRequest.builder()
                .extraFields(List.of("url"))
                .queryField("query")
                .build();
        assertEquals(List.of("url", "query"), request.effectiveOutputFields());
    }

    @Test
    void effectiveOutputFieldsDefaultsToQueryField() {
        var request = SimilaritySearchRequest.builder().build();
        assertEquals(List.of("query"), request.effectiveOutputFields());
    }

    @Test
    void newFieldsDefault() {
        var request = SimilaritySearchRequest.builder().build();
        assertEquals(5, request.topK);
        assertEquals(0d, request.threshold);
        assertEquals("vector", request.vectorField);
        assertFalse(request.includeVector);
    }
}
