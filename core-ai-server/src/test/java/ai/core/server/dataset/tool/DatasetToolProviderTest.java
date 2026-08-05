package ai.core.server.dataset.tool;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.domain.DatasetType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasetToolProviderTest {
    private final DatasetService datasetService = mock(DatasetService.class);
    private final DatasetRecordService recordService = mock(DatasetRecordService.class);

    private Dataset dataset(String id, DatasetType type) {
        var d = new Dataset();
        d.id = id;
        d.type = type;
        return d;
    }

    private DatasetAccessRegistry registry(List<AgentDatasetConfig> configs) {
        return DatasetAccessRegistry.from(configs);
    }

    private AgentDatasetConfig config(String datasetId, DatasetPermission permission) {
        var c = new AgentDatasetConfig();
        c.datasetId = datasetId;
        c.permission = permission;
        return c;
    }

    @Test
    void sessionOnlyAssemblesStateTools() {
        when(datasetService.get("ds1")).thenReturn(dataset("ds1", DatasetType.SESSION));
        var provider = new DatasetToolProvider(datasetService, recordService,
                registry(List.of(config("ds1", DatasetPermission.WRITE))), "agent1", "run1");

        var names = Set.copyOf(provider.provide().keySet());
        assertEquals(Set.of("get_session_state", "set_session_state", "update_session_state"), names);
    }

    @Test
    void sessionReadOnlyAssemblesGetOnly() {
        when(datasetService.get("ds1")).thenReturn(dataset("ds1", DatasetType.SESSION));
        var provider = new DatasetToolProvider(datasetService, recordService,
                registry(List.of(config("ds1", DatasetPermission.READ))), "agent1", "run1");

        var names = Set.copyOf(provider.provide().keySet());
        assertEquals(Set.of("get_session_state"), names);
    }

    @Test
    void generalOnlyAssemblesRecordTools() {
        when(datasetService.get("ds1")).thenReturn(dataset("ds1", DatasetType.GENERAL));
        var provider = new DatasetToolProvider(datasetService, recordService,
                registry(List.of(config("ds1", DatasetPermission.WRITE))), "agent1", "run1");

        var names = Set.copyOf(provider.provide().keySet());
        assertEquals(Set.of("query_dataset_records", "insert_dataset_record", "update_dataset_record"), names);
        assertTrue(names.stream().noneMatch(n -> n.contains("session_state")));
    }

    @Test
    void generalFullAssemblesDeleteTool() {
        when(datasetService.get("ds1")).thenReturn(dataset("ds1", DatasetType.GENERAL));
        var provider = new DatasetToolProvider(datasetService, recordService,
                registry(List.of(config("ds1", DatasetPermission.FULL))), "agent1", "run1");

        assertTrue(provider.provide().containsKey("delete_dataset_record"));
    }

    @Test
    void mixedConfigAssemblesBothToolSets() {
        when(datasetService.get("ds1")).thenReturn(dataset("ds1", DatasetType.SESSION));
        when(datasetService.get("ds2")).thenReturn(dataset("ds2", DatasetType.GENERAL));
        var provider = new DatasetToolProvider(datasetService, recordService,
                registry(List.of(config("ds1", DatasetPermission.WRITE), config("ds2", DatasetPermission.WRITE))), "agent1", "run1");

        var names = Set.copyOf(provider.provide().keySet());
        assertEquals(Set.of("get_session_state", "set_session_state", "update_session_state",
                "query_dataset_records", "insert_dataset_record", "update_dataset_record"), names);
    }
}
