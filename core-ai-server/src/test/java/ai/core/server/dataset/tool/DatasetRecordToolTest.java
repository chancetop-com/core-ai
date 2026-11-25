package ai.core.server.dataset.tool;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.domain.DatasetType;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetRecordToolTest {
    private final DatasetService datasetService = mock(DatasetService.class);
    private final DatasetRecordService recordService = mock(DatasetRecordService.class);

    private DatasetAccessRegistry registry(DatasetPermission permission) {
        var config = new AgentDatasetConfig();
        config.datasetId = "gone";
        config.permission = permission;
        return DatasetAccessRegistry.from(List.of(config));
    }

    private Dataset generalDataset(String id) {
        var dataset = new Dataset();
        dataset.id = id;
        dataset.type = DatasetType.GENERAL;
        return dataset;
    }

    @Test
    void accessErrorRejectsDeletedDataset() {
        when(datasetService.get("gone")).thenReturn(null);

        assertEquals("dataset not found: gone", QueryDatasetRecordsTool.datasetRecordAccessError(datasetService, "gone"));
    }

    @Test
    void accessErrorAllowsExistingGeneralDataset() {
        when(datasetService.get("ds1")).thenReturn(generalDataset("ds1"));

        assertNull(QueryDatasetRecordsTool.datasetRecordAccessError(datasetService, "ds1"));
    }

    @Test
    void insertRejectsDeletedDataset() {
        when(datasetService.get("gone")).thenReturn(null);
        var tool = InsertDatasetRecordTool.create("agent1", null, datasetService, recordService, registry(DatasetPermission.WRITE));

        var result = tool.execute("{\"dataset_id\":\"gone\",\"data\":{\"field\":\"value\"}}");

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("dataset not found"));
        verify(recordService, never()).insert(any());
    }

    @Test
    void updateRejectsDeletedDataset() {
        when(datasetService.get("gone")).thenReturn(null);
        var tool = UpdateDatasetRecordTool.create(datasetService, recordService, registry(DatasetPermission.WRITE));

        var result = tool.execute("{\"dataset_id\":\"gone\",\"record_id\":\"r1\",\"data\":{\"field\":\"value\"}}");

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("dataset not found"));
        verify(recordService, never()).update(any(), any(), any(), any());
    }

    @Test
    void deleteRejectsDeletedDataset() {
        when(datasetService.get("gone")).thenReturn(null);
        var tool = DeleteDatasetRecordTool.create(datasetService, recordService, registry(DatasetPermission.FULL));

        var result = tool.execute("{\"dataset_id\":\"gone\",\"record_id\":\"r1\"}");

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("dataset not found"));
        verify(recordService, never()).delete(any(), any());
    }

    @Test
    void queryRejectsDeletedDataset() {
        when(datasetService.get("gone")).thenReturn(null);
        var tool = QueryDatasetRecordsTool.create(datasetService, recordService, registry(DatasetPermission.READ));

        var result = tool.execute("{\"dataset_id\":\"gone\"}");

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("dataset not found"));
        verify(recordService, never()).query(any());
    }

    @Test
    void renameRejectsDeletedDataset() {
        when(datasetService.get("gone")).thenReturn(null);
        var tool = RenameDatasetFieldTool.create(datasetService, registry(DatasetPermission.WRITE));

        var result = tool.execute("{\"dataset_id\":\"gone\",\"from\":\"old\",\"to\":\"new\"}");

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("dataset not found"));
        verify(datasetService, never()).renameField(any(), any(), any(), any());
    }
}
