package ai.core.server.project;

import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class ProjectAttributionStoreTest {
    private static ProjectSubjectAttribution row(String projectId, String subjectId, String type, String targetId) {
        var row = new ProjectSubjectAttribution();
        row.id = "a-" + targetId + "-" + subjectId;
        row.projectId = projectId;
        row.subjectId = subjectId;
        row.targetType = type;
        row.targetId = targetId;
        return row;
    }

    private ProjectAttributionStore store;
    private MongoCollection<ProjectSubjectAttribution> attributions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new ProjectAttributionStore();
        attributions = (MongoCollection<ProjectSubjectAttribution>) mock(MongoCollection.class);
        store.attributionCollection = attributions;
        store.subjectCollection = (MongoCollection<ProjectSubject>) mock(MongoCollection.class);
        when(attributions.find(any(Query.class))).thenReturn(List.of());
        when(attributions.count(any(Bson.class))).thenReturn(0L);
    }

    @Test
    void fileGetsOneHomePerProject() {
        when(attributions.find(any(Query.class))).thenReturn(List.of(row("p-1", "s-other", "file", "f-1")));

        var result = store.attribute("p-1", "s-1", "file", "f-1", ProjectAttributionStore.SOURCE_INHERITED);

        assertEquals(ProjectAttributionStore.Result.CONFLICT, result);
        verify(attributions, never()).insert(any());
    }

    @Test
    void sameFileSameSubjectIsIdempotent() {
        when(attributions.find(any(Query.class))).thenReturn(List.of(row("p-1", "s-1", "file", "f-1")));

        assertEquals(ProjectAttributionStore.Result.EXISTS, store.attribute("p-1", "s-1", "file", "f-1", ProjectAttributionStore.SOURCE_CASCADE));
        verify(attributions, never()).insert(any());
    }

    @Test
    void insertsDenormalizedRow() {
        var result = store.attribute("p-1", "s-1", "file", "f-1", ProjectAttributionStore.SOURCE_UPLOAD);

        assertEquals(ProjectAttributionStore.Result.INSERTED, result);
        var inserted = ArgumentCaptor.forClass(ProjectSubjectAttribution.class);
        verify(attributions).insert(inserted.capture());
        assertEquals("p-1", inserted.getValue().projectId);
        assertEquals("s-1", inserted.getValue().subjectId);
        assertEquals("file", inserted.getValue().targetType);
        assertEquals("f-1", inserted.getValue().targetId);
        assertEquals(ProjectAttributionStore.SOURCE_UPLOAD, inserted.getValue().source);
    }

    @Test
    void sessionsKeepMultiSubjectSemantics() {
        // a second subject for the same session is allowed: only the exact (subject, target) pair dedupes
        when(attributions.count(any(Bson.class))).thenReturn(0L);
        assertEquals(ProjectAttributionStore.Result.INSERTED, store.attribute("p-1", "s-2", "session", "sess-1", ProjectAttributionStore.SOURCE_ATTRIBUTOR));
        when(attributions.count(any(Bson.class))).thenReturn(1L);
        assertEquals(ProjectAttributionStore.Result.EXISTS, store.attribute("p-1", "s-2", "session", "sess-1", ProjectAttributionStore.SOURCE_ATTRIBUTOR));
    }

    @Test
    void moveFileReplacesHomeOrUnassigns() {
        store.moveFile("p-1", "s-2", "f-1", ProjectAttributionStore.SOURCE_MANUAL);
        verify(attributions).delete(any(Bson.class));
        var inserted = ArgumentCaptor.forClass(ProjectSubjectAttribution.class);
        verify(attributions).insert(inserted.capture());
        assertEquals("s-2", inserted.getValue().subjectId);
        assertEquals(ProjectAttributionStore.SOURCE_MANUAL, inserted.getValue().source);

        store.moveFile("p-1", null, "f-1", ProjectAttributionStore.SOURCE_MANUAL);
        verify(attributions, org.mockito.Mockito.times(2)).delete(any(Bson.class));
        verify(attributions, org.mockito.Mockito.times(1)).insert(any());
    }

    @Test
    void fileSubjectsMapsFileToSubject() {
        when(attributions.find(any(Query.class))).thenReturn(List.of(row("p-1", "s-1", "file", "f-1"), row("p-1", "s-2", "file", "f-2")));

        var map = store.fileSubjects("p-1");

        assertEquals("s-1", map.get("f-1"));
        assertEquals("s-2", map.get("f-2"));
    }
}
