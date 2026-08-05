package ai.core.server.dataset.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.domain.DatasetRecord;
import ai.core.server.domain.DatasetType;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionStateToolTest {
    private final DatasetService datasetService = mock(DatasetService.class);
    private final DatasetRecordService recordService = mock(DatasetRecordService.class);

    private Dataset sessionDataset(String id) {
        var dataset = new Dataset();
        dataset.id = id;
        dataset.type = DatasetType.SESSION;
        return dataset;
    }

    private Dataset generalDataset(String id) {
        var dataset = new Dataset();
        dataset.id = id;
        dataset.type = DatasetType.GENERAL;
        return dataset;
    }

    private DatasetAccessRegistry registry(DatasetPermission permission) {
        var config = new AgentDatasetConfig();
        config.datasetId = "ds1";
        config.permission = permission;
        return DatasetAccessRegistry.from(List.of(config));
    }

    private ExecutionContext context(String sessionId) {
        return ExecutionContext.builder().sessionId(sessionId).userId("u1").build();
    }

    @Test
    void getRequiresSessionContext() {
        var tool = GetSessionStateTool.create(datasetService, recordService, registry(DatasetPermission.READ));

        var result = tool.execute("{\"dataset_id\":\"ds1\"}");

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("session context required"));
    }

    @Test
    void getDeniesUnknownDataset() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        var tool = GetSessionStateTool.create(datasetService, recordService, DatasetAccessRegistry.from(List.of()));
        when(datasetService.get("other")).thenReturn(sessionDataset("other"));

        var result = tool.execute("{\"dataset_id\":\"other\"}", context("s1"));

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("access denied"));
    }

    @Test
    void getRejectsGeneralDataset() {
        when(datasetService.get("ds1")).thenReturn(generalDataset("ds1"));
        var tool = GetSessionStateTool.create(datasetService, recordService, registry(DatasetPermission.READ));

        var result = tool.execute("{\"dataset_id\":\"ds1\"}", context("s1"));

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("not a session dataset"));
    }

    @Test
    void getReturnsNullStateWhenNoRecord() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        when(recordService.queryBySession("ds1", "s1")).thenReturn(Optional.empty());
        var tool = GetSessionStateTool.create(datasetService, recordService, registry(DatasetPermission.READ));

        var result = tool.execute("{\"dataset_id\":\"ds1\"}", context("s1"));

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        var response = JsonUtil.toMap(result.getResult());
        assertNull(response.get("state"));
        assertEquals("s1", response.get("session_id"));
    }

    @Test
    void getReturnsStoredState() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        var record = new DatasetRecord();
        record.data = "{\"menu\":\"pizza\"}";
        when(recordService.queryBySession("ds1", "s1")).thenReturn(Optional.of(record));
        var tool = GetSessionStateTool.create(datasetService, recordService, registry(DatasetPermission.READ));

        var result = tool.execute("{\"dataset_id\":\"ds1\"}", context("s1"));

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        var response = JsonUtil.toMap(result.getResult());
        @SuppressWarnings("unchecked")
        var state = (Map<String, Object>) response.get("state");
        assertEquals("pizza", state.get("menu"));
    }

    @Test
    void setRequiresWritePermission() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        var tool = SetSessionStateTool.create("agent1", datasetService, recordService, registry(DatasetPermission.READ));

        var result = tool.execute("{\"dataset_id\":\"ds1\",\"data\":{\"menu\":\"pizza\"}}", context("s1"));

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("write access denied"));
        verify(recordService, never()).upsertBySession(any(), any(), any(), any(), any());
    }

    @Test
    void setRejectsGeneralDataset() {
        when(datasetService.get("ds1")).thenReturn(generalDataset("ds1"));
        var tool = SetSessionStateTool.create("agent1", datasetService, recordService, registry(DatasetPermission.WRITE));

        var result = tool.execute("{\"dataset_id\":\"ds1\",\"data\":{\"menu\":\"pizza\"}}", context("s1"));

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("not a session dataset"));
    }

    @Test
    void setRequiresNonEmptyData() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        var tool = SetSessionStateTool.create("agent1", datasetService, recordService, registry(DatasetPermission.WRITE));

        var result = tool.execute("{\"dataset_id\":\"ds1\",\"data\":{}}", context("s1"));

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("data is required"));
    }

    @Test
    void setPersistsFullState() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        var tool = SetSessionStateTool.create("agent1", datasetService, recordService, registry(DatasetPermission.WRITE));

        var result = tool.execute("{\"dataset_id\":\"ds1\",\"data\":{\"menu\":\"pizza\",\"business_type\":\"QSR\"}}", context("s1"));

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getResult().contains("\"status\":\"saved\""));
        verify(recordService).upsertBySession("ds1", "s1", "{\"menu\":\"pizza\",\"business_type\":\"QSR\"}", "agent1", "u1");
    }

    @Test
    void setRejectsOversizedState() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        var tool = SetSessionStateTool.create("agent1", datasetService, recordService, registry(DatasetPermission.WRITE));
        var big = new StringBuilder();
        big.append("{\"blob\":\"")
            .append("x".repeat(DatasetRecordService.MAX_STATE_BYTES))
            .append("\"}");

        var result = tool.execute("{\"dataset_id\":\"ds1\",\"data\":" + big + "}", context("s1"));

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("too large"));
    }

    @Test
    void getFiltersStateByRequestedFields() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        var record = new DatasetRecord();
        record.data = "{\"menu\":\"pizza\",\"business_type\":\"QSR\"}";
        when(recordService.queryBySession("ds1", "s1")).thenReturn(Optional.of(record));
        var tool = GetSessionStateTool.create(datasetService, recordService, registry(DatasetPermission.READ));

        var result = tool.execute("{\"dataset_id\":\"ds1\",\"fields\":\"business_type\"}", context("s1"));

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        var response = JsonUtil.toMap(result.getResult());
        @SuppressWarnings("unchecked")
        var state = (Map<String, Object>) response.get("state");
        assertEquals(1, state.size());
        assertEquals("QSR", state.get("business_type"));
    }

    @Test
    void updateMergesPartialFields() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        var tool = UpdateSessionStateTool.create("agent1", datasetService, recordService, registry(DatasetPermission.WRITE));

        var result = tool.execute("{\"dataset_id\":\"ds1\",\"data\":{\"menu_source\":\"OFFLINE\"}}", context("s1"));

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getResult().contains("\"status\":\"updated\""));
        assertTrue(result.getResult().contains("menu_source"));
        verify(recordService).patchBySession("ds1", "s1", "{\"menu_source\":\"OFFLINE\"}", "agent1", "u1");
    }

    @Test
    void updateRequiresWritePermission() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        var tool = UpdateSessionStateTool.create("agent1", datasetService, recordService, registry(DatasetPermission.READ));

        var result = tool.execute("{\"dataset_id\":\"ds1\",\"data\":{\"menu_source\":\"OFFLINE\"}}", context("s1"));

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("write access denied"));
        verify(recordService, never()).patchBySession(any(), any(), any(), any(), any());
    }

    @Test
    void updatePropagatesMergedStateTooLargeError() {
        when(datasetService.get("ds1")).thenReturn(sessionDataset("ds1"));
        doThrow(new IllegalArgumentException("state too large, max 256 KB"))
                .when(recordService).patchBySession(any(), any(), any(), any(), any());
        var tool = UpdateSessionStateTool.create("agent1", datasetService, recordService, registry(DatasetPermission.WRITE));

        var result = tool.execute("{\"dataset_id\":\"ds1\",\"data\":{\"menu_source\":\"OFFLINE\"}}", context("s1"));

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("too large"));
    }
}
