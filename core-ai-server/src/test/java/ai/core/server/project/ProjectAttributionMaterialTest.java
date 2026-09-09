package ai.core.server.project;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Convergence rules of the attribution collection: a record is offered once per version, grown
 * attributed records are re-opened, the forward cursor never passes a record that did not fit.
 *
 * @author stephen
 */
class ProjectAttributionMaterialTest {
    private static final ZonedDateTime T1 = ZonedDateTime.of(2026, 9, 1, 10, 0, 0, 0, java.time.ZoneId.of("UTC"));
    private static final ZonedDateTime T2 = T1.plusHours(1);
    private static final ZonedDateTime T3 = T1.plusHours(2);

    @Test
    void neverOfferedRecordIsOffered() {
        var material = new ProjectAttributionMaterial("p-1", 1000);
        assertEquals(ProjectAttributionMaterial.Decision.OFFER, material.consider("session", "s-1", T1, null, false));
        assertTrue(material.contains("session", "s-1"));
        assertEquals(1, material.offered().size());
        assertTrue(material.grown().isEmpty());
    }

    @Test
    void offeredAndUnchangedRecordIsSkipped() {
        var material = new ProjectAttributionMaterial("p-1", 1000);
        var state = new ProjectTargetScanStore.ScanState(T2, false);
        assertEquals(ProjectAttributionMaterial.Decision.SKIP, material.consider("session", "s-1", T2, state, false));
        assertTrue(material.isEmpty());
    }

    @Test
    void attributedWithoutMarkerIsNotOfferedAgain() {
        var material = new ProjectAttributionMaterial("p-1", 1000);
        var state = new ProjectTargetScanStore.ScanState(T2, true);   // deterministic binding at T2
        assertEquals(ProjectAttributionMaterial.Decision.SKIP, material.consider("run", "r-1", T1, state, false));
    }

    @Test
    void grownAttributedRecordIsReofferedAndReopened() {
        var material = new ProjectAttributionMaterial("p-1", 1000);
        var state = new ProjectTargetScanStore.ScanState(T1, true);
        assertEquals(ProjectAttributionMaterial.Decision.OFFER, material.consider("session", "s-1", T3, state, false));
        assertEquals(1, material.grown().size());
        assertEquals("s-1", material.grown().getFirst().targetId());
        assertEquals(T3, material.offered().getFirst().materialAt());
    }

    @Test
    void grownUnattributedRecordIsReofferedButNotReopened() {
        var material = new ProjectAttributionMaterial("p-1", 1000);
        var state = new ProjectTargetScanStore.ScanState(T1, false);
        assertEquals(ProjectAttributionMaterial.Decision.OFFER, material.consider("session", "s-1", T2, state, false));
        assertTrue(material.grown().isEmpty());
    }

    @Test
    void secondPassSkipsRecordCollectedByFirstPass() {
        var material = new ProjectAttributionMaterial("p-1", 1000);
        material.consider("session", "s-1", T2, null, false);
        assertEquals(ProjectAttributionMaterial.Decision.SKIP, material.consider("session", "s-1", T2, null, true));
        assertEquals(T2, material.forwardLatest());   // still counts as covered for the cursor
    }

    @Test
    void forwardCursorAdvancesOverOfferedAndSkippedRecordsOnly() {
        var material = new ProjectAttributionMaterial("p-1", 1000);
        material.consider("run", "r-1", T1, null, false);          // fresh pass: no cursor movement
        assertNull(material.forwardLatest());
        material.consider("run", "r-2", T2, new ProjectTargetScanStore.ScanState(T2, false), true);   // covered
        material.consider("run", "r-3", T3, null, true);           // offered
        assertEquals(T3, material.forwardLatest());
    }

    @Test
    void digestCapStopsCollectionWithoutMovingCursor() {
        var material = new ProjectAttributionMaterial("p-1", 10);
        material.consider("run", "r-1", T1, null, true);
        material.append("0123456789ab");   // over the cap
        assertEquals(ProjectAttributionMaterial.Decision.STOP, material.consider("run", "r-2", T2, null, true));
        assertEquals(T1, material.forwardLatest());   // the cursor must not pass r-2
        assertFalse(material.contains("run", "r-2"));
    }

    @Test
    void filesAreMatchedThroughTheArtifactList() {
        var material = new ProjectAttributionMaterial("p-1", 1000);
        material.addFile("f-1");
        assertTrue(material.contains("file", "f-1"));
        assertFalse(material.contains("file", "f-2"));
    }
}
