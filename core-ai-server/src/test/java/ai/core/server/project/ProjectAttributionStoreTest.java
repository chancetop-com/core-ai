package ai.core.server.project;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
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

    // the default driver registry has no ZonedDateTime codec (core-ng registers its own at runtime)
    private static String json(Bson bson) {
        var registry = org.bson.codecs.configuration.CodecRegistries.fromRegistries(
            org.bson.codecs.configuration.CodecRegistries.fromCodecs(new ZonedDateTimeCodec()), com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
        return bson.toBsonDocument(org.bson.BsonDocument.class, registry).toJson();
    }

    private ProjectAttributionStore store;
    private MongoCollection<ProjectSubjectAttribution> attributions;
    private MongoCollection<FileRecord> files;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new ProjectAttributionStore();
        attributions = (MongoCollection<ProjectSubjectAttribution>) mock(MongoCollection.class);
        store.attributionCollection = attributions;
        store.subjectCollection = (MongoCollection<ProjectSubject>) mock(MongoCollection.class);
        files = (MongoCollection<FileRecord>) mock(MongoCollection.class);
        store.fileRecordCollection = files;
        var record = new FileRecord();
        record.id = "f-1";
        record.createdAt = ZonedDateTime.parse("2026-08-15T08:00:00Z");
        when(files.find(any(Query.class))).thenReturn(List.of(record));
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
        // no metadata supplied: the file time is read from file_records so the row is pageable
        assertEquals(ZonedDateTime.parse("2026-08-15T08:00:00Z"), inserted.getValue().targetCreatedAt);
    }

    @Test
    void attributeFileKeepsSuppliedMetadataWithoutLookup() {
        var at = ZonedDateTime.parse("2026-09-01T10:00:00Z");
        store.attributeFile("p-1", "s-1", "f-1", ProjectAttributionStore.SOURCE_INHERITED, "agent-1", at);

        var inserted = ArgumentCaptor.forClass(ProjectSubjectAttribution.class);
        verify(attributions).insert(inserted.capture());
        assertEquals("agent-1", inserted.getValue().agentId);
        assertEquals(at, inserted.getValue().targetCreatedAt);
        verify(files, never()).find(any(Query.class));
    }

    @Test
    void missingFileWritesNoRow() {
        // the file expired / was deleted: homing it would create a row the list cannot render but the count would include
        when(files.find(any(Query.class))).thenReturn(List.of());

        var result = store.attributeFile("p-1", "s-1", "f-gone", ProjectAttributionStore.SOURCE_CASCADE, "agent-1", null);

        assertEquals(ProjectAttributionStore.Result.MISSING, result);
        verify(attributions, never()).insert(any());
    }

    @Test
    void filedFilterExcludesRowsWithoutFileTime() {
        store.filedFileCount("p-1", null, null, null);

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(attributions).count(filter.capture());
        var json = json(filter.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"target_created_at\": {\"$gte\""), json);
    }

    @Test
    void filedFilesPagesOnTheAttributionTable() {
        store.filedFiles("p-1", "s-1", null, null, 100, 50);

        var query = ArgumentCaptor.forClass(Query.class);
        verify(attributions).find(query.capture());
        assertEquals(100, query.getValue().skip);
        assertEquals(50, query.getValue().limit);
        var sort = query.getValue().sort.toBsonDocument(org.bson.BsonDocument.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
        assertEquals(-1, sort.getInt32("target_created_at").getValue());
        var filter = json(query.getValue().filter);
        org.junit.jupiter.api.Assertions.assertTrue(filter.contains("\"subject_id\": \"s-1\"") && filter.contains("\"target_type\": \"file\""), filter);
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
        var previous = row("p-1", "s-1", "file", "f-1");
        previous.agentId = "agent-1";
        previous.targetCreatedAt = ZonedDateTime.parse("2026-07-01T00:00:00Z");
        when(attributions.find(any(Query.class))).thenReturn(List.of(previous)).thenReturn(List.of());
        store.moveFile("p-1", "s-2", "f-1", ProjectAttributionStore.SOURCE_MANUAL);
        verify(attributions).delete(any(Bson.class));
        var inserted = ArgumentCaptor.forClass(ProjectSubjectAttribution.class);
        verify(attributions).insert(inserted.capture());
        assertEquals("s-2", inserted.getValue().subjectId);
        assertEquals(ProjectAttributionStore.SOURCE_MANUAL, inserted.getValue().source);
        // the re-homed report keeps its date and producer
        assertEquals("agent-1", inserted.getValue().agentId);
        assertEquals(previous.targetCreatedAt, inserted.getValue().targetCreatedAt);

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

    private static final class ZonedDateTimeCodec implements org.bson.codecs.Codec<ZonedDateTime> {
        @Override
        public ZonedDateTime decode(org.bson.BsonReader reader, org.bson.codecs.DecoderContext context) {
            return java.time.Instant.ofEpochMilli(reader.readDateTime()).atZone(java.time.ZoneOffset.UTC);
        }

        @Override
        public void encode(org.bson.BsonWriter writer, ZonedDateTime value, org.bson.codecs.EncoderContext context) {
            writer.writeDateTime(value.toInstant().toEpochMilli());
        }

        @Override
        public Class<ZonedDateTime> getEncoderClass() {
            return ZonedDateTime.class;
        }
    }
}
