package ai.core.server.comparison;

import ai.core.api.server.comparison.AgentComparisonView;
import ai.core.api.server.comparison.EndAgentComparisonRequest;
import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.comparison.domain.ComparisonStatus;
import ai.core.server.comparison.domain.ComparisonVariant;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lifecycle exits of a comparison: ending (with the adoption is delegated to
 * {@link ComparisonAdoptionService}) and the two undo hatches — resume puts an accidentally ended
 * experiment back to running, revert discards an accidental adoption.
 *
 * @author stephen
 */
class AgentComparisonServiceTest {
    private static final String COMPARISON_ID = "cmp-1";
    private static final String AGENT_ID = "agent-1";
    private static final String USER_ID = "user-1";
    private static final ZonedDateTime TEST_START = ZonedDateTime.parse("2026-10-16T02:00:00Z");

    private static EndAgentComparisonRequest request(String adopt, boolean force) {
        var request = new EndAgentComparisonRequest();
        request.adopt = adopt;
        request.force = force;
        return request;
    }

    private static AgentPublishedConfig config(String model) {
        var config = new AgentPublishedConfig();
        config.model = model;
        config.systemPrompt = "prompt-" + model;
        return config;
    }

    private static ComparisonVariant variant(String model) {
        var variant = new ComparisonVariant();
        variant.label = model;
        variant.config = config(model);
        return variant;
    }

    private static AgentComparison comparison(ComparisonStatus status) {
        var comparison = new AgentComparison();
        comparison.id = COMPARISON_ID;
        comparison.agentId = AGENT_ID;
        comparison.createdBy = USER_ID;
        comparison.status = status;
        comparison.agentPublishedAt = TEST_START;
        comparison.variantA = variant("a-model");
        comparison.variantB = variant("b-model");
        return comparison;
    }

    @SuppressWarnings("unchecked")
    private final MongoCollection<AgentComparison> comparisons = mock(MongoCollection.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<User> users = mock(MongoCollection.class);
    private final ComparisonViewAssembler assembler = mock(ComparisonViewAssembler.class);
    private final ComparisonAdoptionService adoptionService = mock(ComparisonAdoptionService.class);
    private final AgentComparisonService service = new AgentComparisonService();

    @BeforeEach
    void setUp() {
        service.comparisonCollection = comparisons;
        service.userCollection = users;
        service.assembler = assembler;
        service.adoptionService = adoptionService;
        when(assembler.toDetailView(any(), anyBoolean())).thenReturn(new AgentComparisonView());
    }

    @Test
    void adoptingDelegatesToTheAdoptionServiceAndRecordsTheVariant() {
        var comparison = comparison(ComparisonStatus.RUNNING);
        when(comparisons.get(COMPARISON_ID)).thenReturn(Optional.of(comparison));

        service.end(COMPARISON_ID, request("b", false), USER_ID);

        verify(adoptionService).adopt(comparison, "b", false, USER_ID);
        assertEquals("b", comparison.adoptedVariant);
        assertEquals(ComparisonStatus.ENDED, comparison.status);
        verify(comparisons).replace(comparison);
    }

    @Test
    void endingWithoutAdoptingKeepsAnEarlierAdoptionOnRecord() {
        var comparison = comparison(ComparisonStatus.RUNNING);
        comparison.adoptedVariant = "b";
        comparison.preAdoptConfig = config("a-model");
        when(comparisons.get(COMPARISON_ID)).thenReturn(Optional.of(comparison));

        service.end(COMPARISON_ID, request(null, false), USER_ID);

        assertEquals(ComparisonStatus.ENDED, comparison.status);
        assertEquals("b", comparison.adoptedVariant, "the live agent still carries B, so the record must survive");
        verify(adoptionService, never()).adopt(any(), any(), anyBoolean(), any());
    }

    @Test
    void resumeReopensAnEndedComparisonAndDropsTheEndTimestamp() {
        var comparison = comparison(ComparisonStatus.ENDED);
        comparison.endedAt = TEST_START.plusHours(2);
        when(comparisons.get(COMPARISON_ID)).thenReturn(Optional.of(comparison));
        when(comparisons.findOne(any(Bson.class))).thenReturn(Optional.empty());

        service.resume(COMPARISON_ID, USER_ID);

        assertEquals(ComparisonStatus.RUNNING, comparison.status);
        assertNull(comparison.endedAt);
        verify(comparisons).replace(comparison);
    }

    @Test
    void resumeRefusesWhenTheAgentAlreadyHasAnotherRunningComparison() {
        var comparison = comparison(ComparisonStatus.ENDED);
        when(comparisons.get(COMPARISON_ID)).thenReturn(Optional.of(comparison));
        var other = new AgentComparison();
        other.id = "cmp-2";
        when(comparisons.findOne(any(Bson.class))).thenReturn(Optional.of(other));

        assertThrows(BadRequestException.class, () -> service.resume(COMPARISON_ID, USER_ID));
        assertEquals(ComparisonStatus.ENDED, comparison.status);
        verify(comparisons, never()).replace(any());
    }

    @Test
    void resumeRefusesAComparisonThatHasNotEnded() {
        when(comparisons.get(COMPARISON_ID)).thenReturn(Optional.of(comparison(ComparisonStatus.RUNNING)));

        assertThrows(BadRequestException.class, () -> service.resume(COMPARISON_ID, USER_ID));
    }

    @Test
    void revertAdoptDelegatesAndClearsTheSnapshot() {
        var comparison = comparison(ComparisonStatus.ENDED);
        comparison.adoptedVariant = "b";
        comparison.preAdoptConfig = config("a-model");
        comparison.adoptAgentUpdatedAt = TEST_START.plusHours(1);
        when(comparisons.get(COMPARISON_ID)).thenReturn(Optional.of(comparison));

        service.revertAdopt(COMPARISON_ID, USER_ID);

        verify(adoptionService).revert(comparison, USER_ID);
        assertNull(comparison.adoptedVariant);
        assertNull(comparison.preAdoptConfig);
        assertNull(comparison.adoptAgentUpdatedAt);
        verify(comparisons).replace(comparison);
    }

    @Test
    void revertAdoptRefusesWhenNothingWasAdopted() {
        when(comparisons.get(COMPARISON_ID)).thenReturn(Optional.of(comparison(ComparisonStatus.ENDED)));

        assertThrows(BadRequestException.class, () -> service.revertAdopt(COMPARISON_ID, USER_ID));
        verify(adoptionService, never()).revert(any(), any());
    }
}
