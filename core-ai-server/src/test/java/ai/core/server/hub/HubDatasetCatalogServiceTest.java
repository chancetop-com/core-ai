package ai.core.server.hub;

import ai.core.server.agent.AgentCallAccessPolicy;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetRecord;
import ai.core.server.domain.DatasetType;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import ai.core.utils.JsonUtil;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rules of dataset-scope access: the ref resolves by id or unique name, GENERAL data answers the record operations
 * with the tools' own payloads, SESSION datasets are refused, and audit rows carry no session.
 */
class HubDatasetCatalogServiceTest {
    private static final String USER_ID = "u1";

    private static SchemaField field(String name) {
        var field = new SchemaField();
        field.name = name;
        field.type = SchemaFieldType.STRING;
        return field;
    }

    private final DatasetService datasetService = mock(DatasetService.class);
    private final DatasetRecordService recordService = mock(DatasetRecordService.class);
    private final HubCallAuditService auditService = mock(HubCallAuditService.class);
    private final AgentCallAccessPolicy accessPolicy = mock(AgentCallAccessPolicy.class);
    private final HubDatasetCatalogService service = new HubDatasetCatalogService();

    private final Dataset sessionDataset = dataset("ds-session", "menu-state", DatasetType.SESSION, null);
    private final Dataset generalDataset = dataset("ds-general", "orders", DatasetType.GENERAL, List.of("merchant_id", "amount"));

    @BeforeEach
    void setUp() {
        service.datasetService = datasetService;
        service.datasetRecordService = recordService;
        service.auditService = auditService;
        service.accessPolicy = accessPolicy;

        when(datasetService.get("ds-session")).thenReturn(sessionDataset);
        when(datasetService.get("ds-general")).thenReturn(generalDataset);
        when(accessPolicy.isApiUser(any())).thenReturn(Boolean.FALSE);
        when(auditService.begin(any())).thenReturn("call-1");
    }

    @Test
    void listsDatasetsWithoutASession() {
        when(datasetService.list("orders", 0, 20)).thenReturn(List.of(generalDataset));

        var datasets = service.listDatasets("orders", 0, 20, USER_ID);

        assertEquals(List.of(generalDataset), datasets);
        var request = ArgumentCaptor.forClass(HubCallAuditService.BeginRequest.class);
        verify(auditService).begin(request.capture());
        assertEquals(HubDatasetService.OP_DATASETS_LIST, request.getValue().name());
        assertNull(request.getValue().sessionId());
    }

    @Test
    void resolvesDatasetByUniqueNameAndRejectsAmbiguousNames() {
        when(datasetService.listByExactName("orders")).thenReturn(List.of(generalDataset));
        assertEquals("ds-general", service.getDataset("orders", USER_ID).id);

        when(datasetService.listByExactName("orders")).thenReturn(List.of(
                dataset("ds-a", "orders", DatasetType.GENERAL, null),
                dataset("ds-b", "orders", DatasetType.GENERAL, null)));
        var error = assertThrows(BadRequestException.class, () -> service.getDataset("orders", USER_ID));

        assertEquals("dataset name is ambiguous, pass the dataset id: orders (candidates: ds-a, ds-b)",
                error.getMessage());
    }

    @Test
    void unknownDatasetIsNotFound() {
        when(datasetService.listByExactName("ghost")).thenReturn(List.of());

        var error = assertThrows(NotFoundException.class, () -> service.getDataset("ghost", USER_ID));

        assertEquals("dataset not found: ghost", error.getMessage());
    }

    @Test
    void queriesGeneralDatasetRecordsWithoutSession() {
        when(datasetService.listByExactName("orders")).thenReturn(List.of(generalDataset));
        when(recordService.query(any())).thenReturn(new DatasetRecordService.QueryResult(List.of(record()), 1, false));

        var payload = JsonUtil.toMap(service.queryRecords("orders",
                new HubDatasetService.RecordQuery("{\"amount\":12}", null, null, null, 2, 0), USER_ID));

        var request = ArgumentCaptor.forClass(DatasetRecordService.QueryRequest.class);
        verify(recordService).query(request.capture());
        assertEquals("ds-general", request.getValue().datasetId());
        assertEquals(Map.of("amount", 12), request.getValue().filter());
        assertEquals(2, request.getValue().limit());
        assertEquals("ds-general", payload.get("dataset_id"));
        assertEquals(1, payload.get("total"));

        var audit = ArgumentCaptor.forClass(HubCallAuditService.BeginRequest.class);
        verify(auditService).begin(audit.capture());
        assertNull(audit.getValue().sessionId());
        assertEquals(HubDatasetService.OP_RECORDS_QUERY, audit.getValue().name());
    }

    @Test
    void refusesSessionDatasetOutsideASession() {
        var error = assertThrows(BadRequestException.class,
                () -> service.queryRecords("ds-session", query(null), USER_ID));

        assertEquals("session dataset is not accessible via dataset record tools, "
                + "use get_session_state/set_session_state instead: ds-session", error.getMessage());
    }

    @Test
    void insertsAndDeletesRecordsWithoutSession() {
        when(datasetService.listByExactName("orders")).thenReturn(List.of(generalDataset));
        when(recordService.delete("ds-general", "r1")).thenReturn(Boolean.TRUE);

        service.insertRecord("orders", "{\"merchant_id\":\"M1\",\"amount\":12}", USER_ID);
        service.deleteRecord("orders", "r1", USER_ID);

        var insert = ArgumentCaptor.forClass(DatasetRecordService.InsertRequest.class);
        verify(recordService).insert(insert.capture());
        assertEquals(HubDatasetCatalogService.DIRECT_AGENT_ID, insert.getValue().agentId());
        assertNull(insert.getValue().runId());
        assertEquals(USER_ID, insert.getValue().userId());
        verify(recordService).delete("ds-general", "r1");
    }

    @Test
    void renamesFieldWithoutSession() {
        when(datasetService.listByExactName("orders")).thenReturn(List.of(generalDataset));
        when(datasetService.renameField("ds-general", "amount", "total", USER_ID))
                .thenReturn(new DatasetService.RenameResult(generalDataset, "amount", "total", 3));

        var payload = JsonUtil.toMap(service.renameField("orders", "amount", "total", USER_ID));

        assertEquals("renamed", payload.get("status"));
        assertEquals(3, payload.get("renamed_records"));
    }

    private HubDatasetService.RecordQuery query(String filter) {
        return new HubDatasetService.RecordQuery(filter, null, null, null, null, null);
    }

    private DatasetRecord record() {
        var record = new DatasetRecord();
        record.id = "r1";
        record.datasetId = "ds-general";
        record.runId = "run1";
        record.agentId = "agent1";
        record.runStartedAt = ZonedDateTime.parse("2026-09-20T10:00:00Z");
        record.data = "{\"merchant_id\":\"M1\",\"amount\":12}";
        return record;
    }

    private Dataset dataset(String id, String name, DatasetType type, List<String> schema) {
        var dataset = new Dataset();
        dataset.id = id;
        dataset.name = name;
        dataset.type = type;
        dataset.schema = schema == null ? null : schema.stream().map(HubDatasetCatalogServiceTest::field).toList();
        return dataset;
    }
}
