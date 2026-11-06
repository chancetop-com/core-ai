package ai.core.server.dataset;

import ai.core.server.dataset.store.DatasetMetaStore;
import ai.core.server.dataset.store.DatasetRecordStore;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rename is a service-level operation over records and metadata; the store owns the record rewrite, while the
 * service must refuse ambiguous names before touching any data.
 *
 * @author stephen
 */
class DatasetServiceRenameTest {
    private static SchemaField field(String name, SchemaFieldType type) {
        var field = new SchemaField();
        field.name = name;
        field.type = type;
        field.label = name + " label";
        return field;
    }

    private static Dataset dataset(SchemaField... fields) {
        var dataset = new Dataset();
        dataset.id = "ds-1";
        dataset.name = "test";
        dataset.schema = new ArrayList<>(List.of(fields));
        return dataset;
    }

    private static DatasetService service(Dataset dataset, DatasetRecordStore recordStore) {
        var metaStore = mock(DatasetMetaStore.class);
        when(metaStore.get(dataset.id)).thenReturn(dataset);
        var service = new DatasetService();
        service.datasetMetaStore = metaStore;
        service.datasetRecordStore = recordStore;
        return service;
    }

    @Test
    void renamesTheSchemaFieldAndAllRecords() {
        var dataset = dataset(field("old", SchemaFieldType.STRING));
        var recordStore = mock(DatasetRecordStore.class);
        when(recordStore.renameField("ds-1", "old", "new", "u1")).thenReturn(3L);
        var service = service(dataset, recordStore);

        var result = service.renameField("ds-1", "old", "new", "u1");

        assertEquals(3L, result.records());
        assertEquals("new", result.dataset().schema.getFirst().name);
        assertEquals(SchemaFieldType.STRING, result.dataset().schema.getFirst().type);
        assertEquals("old label", result.dataset().schema.getFirst().label);
        verify(recordStore).renameField("ds-1", "old", "new", "u1");
        verify(service.datasetMetaStore).updateWithSchema(dataset);
    }

    @Test
    void rejectsUnknownOrDuplicateFieldsBeforeTouchingRecords() {
        var dataset = dataset(field("old", SchemaFieldType.STRING), field("new", SchemaFieldType.STRING));
        var recordStore = mock(DatasetRecordStore.class);
        var service = service(dataset, recordStore);

        assertThrows(IllegalArgumentException.class, () -> service.renameField("ds-1", "missing", "other", "u1"));
        assertThrows(IllegalArgumentException.class, () -> service.renameField("ds-1", "old", "new", "u1"));
        assertThrows(IllegalArgumentException.class, () -> service.renameField("ds-1", "old", "old", "u1"));
        verifyNoInteractions(recordStore);
    }
}
