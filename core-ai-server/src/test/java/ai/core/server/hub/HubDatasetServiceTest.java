package ai.core.server.hub;

import ai.core.agent.ExecutionContext;
import ai.core.server.agent.AgentCallAccessPolicy;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.tool.DatasetAccessRegistry;
import ai.core.server.dataset.tool.DeleteDatasetRecordTool;
import ai.core.server.dataset.tool.GetSessionStateTool;
import ai.core.server.dataset.tool.InsertDatasetRecordTool;
import ai.core.server.dataset.tool.QueryDatasetRecordsTool;
import ai.core.server.dataset.tool.SetSessionStateTool;
import ai.core.server.dataset.tool.UpdateDatasetRecordTool;
import ai.core.server.dataset.tool.UpdateSessionStateTool;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.domain.DatasetRecord;
import ai.core.server.domain.DatasetType;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import ai.core.server.session.SessionRegistry;
import ai.core.utils.JsonUtil;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rules of the session-anchored dataset access over the hub, plus the contract that matters most: a hub
 * payload is byte-identical to the one the matching builtin tool produces for the same operation.
 */
class HubDatasetServiceTest {
    private static final String SESSION_ID = "s1";
    private static final String USER_ID = "u1";

    private static SchemaField field(String name) {
        var field = new SchemaField();
        field.name = name;
        field.type = SchemaFieldType.STRING;
        return field;
    }

    private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    private final DatasetService datasetService = mock(DatasetService.class);
    private final DatasetRecordService recordService = mock(DatasetRecordService.class);
    private final HubCallAuditService auditService = mock(HubCallAuditService.class);
    private final AgentCallAccessPolicy accessPolicy = mock(AgentCallAccessPolicy.class);
    private final HubDatasetService service = new HubDatasetService();

    private final Dataset sessionDataset = dataset("ds-session", "menu-state", DatasetType.SESSION, null);
    private final Dataset generalDataset = dataset("ds-general", "orders", DatasetType.GENERAL, List.of("merchant_id", "amount"));

    @BeforeEach
    void setUp() {
        service.sessionRegistry = sessionRegistry;
        service.datasetService = datasetService;
        service.datasetRecordService = recordService;
        service.auditService = auditService;
        service.accessPolicy = accessPolicy;

        when(sessionRegistry.requireAccessible(SESSION_ID, USER_ID)).thenReturn(session(
                datasetConfig("ds-session", DatasetPermission.FULL), datasetConfig("ds-general", DatasetPermission.FULL)));
        when(datasetService.get("ds-session")).thenReturn(sessionDataset);
        when(datasetService.get("ds-general")).thenReturn(generalDataset);
        when(accessPolicy.isApiUser(any())).thenReturn(Boolean.FALSE);
        when(auditService.begin(any())).thenReturn("call-1");
    }

    @Test
    void rejectsSessionOfAnotherUser() {
        when(sessionRegistry.requireAccessible(SESSION_ID, "u2"))
                .thenThrow(new ForbiddenException("session is unavailable"));

        assertThrows(ForbiddenException.class, () -> service.getState(SESSION_ID, "ds-session", null, "u2"));
    }

    @Test
    void deniesDatasetOutsideBindings() {
        var error = assertThrows(ForbiddenException.class,
                () -> service.getState(SESSION_ID, "other-dataset", null, USER_ID));

        assertEquals("access denied to dataset: other-dataset", error.getMessage());
    }

    @Test
    void deniesAmbiguousName() {
        when(sessionRegistry.requireAccessible(SESSION_ID, USER_ID)).thenReturn(session(
                datasetConfig("ds-session", DatasetPermission.FULL), datasetConfig("ds-dup", DatasetPermission.FULL)));
        when(datasetService.get("ds-dup")).thenReturn(dataset("ds-dup", "menu-state", DatasetType.SESSION, null));

        var error = assertThrows(BadRequestException.class,
                () -> service.getState(SESSION_ID, "menu-state", null, USER_ID));

        assertEquals("dataset name is ambiguous, pass the dataset id: menu-state (candidates: ds-dup, ds-session)",
                error.getMessage());
    }

    @Test
    void resolvesNameToCanonicalId() {
        var record = new DatasetRecord();
        record.data = "{\"menu\":\"pizza\"}";
        when(recordService.queryBySession("ds-session", SESSION_ID)).thenReturn(Optional.of(record));

        var payload = JsonUtil.toMap(service.getState(SESSION_ID, "menu-state", null, USER_ID));

        assertEquals("ds-session", payload.get("dataset_id"));
        verify(recordService).queryBySession("ds-session", SESSION_ID);
    }

    @Test
    void rejectsStateOpOnRecordDataset() {
        var error = assertThrows(BadRequestException.class, () -> service.getState(SESSION_ID, "orders", null, USER_ID));

        assertEquals("not a session dataset, use dataset record tools instead: orders", error.getMessage());
    }

    @Test
    void rejectsRecordOpOnSessionDataset() {
        var error = assertThrows(BadRequestException.class,
                () -> service.queryRecords(SESSION_ID, "menu-state", query(null), USER_ID));

        assertEquals("session dataset is not accessible via dataset record tools, "
                + "use get_session_state/set_session_state instead: ds-session", error.getMessage());
    }

    @Test
    void deniesWriteWithoutWritePermission() {
        when(sessionRegistry.requireAccessible(SESSION_ID, USER_ID))
                .thenReturn(session(datasetConfig("ds-session", DatasetPermission.READ)));

        var error = assertThrows(ForbiddenException.class,
                () -> service.saveState(SESSION_ID, "menu-state", "{\"menu\":\"pizza\"}", USER_ID));

        assertEquals("write access denied to dataset: menu-state", error.getMessage());
        verify(recordService, never()).upsertBySession(any(), any(), any(), any(), any());
    }

    @Test
    void deniesDeleteWithoutFullPermission() {
        when(sessionRegistry.requireAccessible(SESSION_ID, USER_ID))
                .thenReturn(session(datasetConfig("ds-general", DatasetPermission.WRITE)));

        var error = assertThrows(ForbiddenException.class,
                () -> service.deleteRecord(SESSION_ID, "orders", "r1", USER_ID));

        assertEquals("delete access denied to dataset: orders", error.getMessage());
        verify(recordService, never()).delete(any(), any());
    }

    @Test
    void rejectsOversizedState() {
        var error = assertThrows(BadRequestException.class,
                () -> service.saveState(SESSION_ID, "menu-state", "{\"blob\":\"" + "x".repeat(300 * 1024) + "\"}", USER_ID));

        assertEquals("state too large, max 256 KB", error.getMessage());
    }

    @Test
    void rejectsEmptyData() {
        var error = assertThrows(BadRequestException.class,
                () -> service.saveState(SESSION_ID, "menu-state", "{}", USER_ID));

        assertEquals("data is required and must not be empty", error.getMessage());
    }

    @Test
    void rejectsFilterThatIsNotJsonObject() {
        var error = assertThrows(BadRequestException.class,
                () -> service.queryRecords(SESSION_ID, "orders", query("[1]"), USER_ID));

        assertTrue(error.getMessage().startsWith("invalid filter, must be a JSON object like {\"status\": \"done\"}"));
    }

    @Test
    void returnsNotFoundForMissingRecord() {
        when(recordService.update(eq("ds-general"), eq("r1"), any(), eq(USER_ID))).thenReturn(Boolean.FALSE);
        when(recordService.delete("ds-general", "r1")).thenReturn(Boolean.FALSE);

        var update = assertThrows(NotFoundException.class,
                () -> service.updateRecord(SESSION_ID, "orders", "r1", "{\"amount\":1}", USER_ID));
        var delete = assertThrows(NotFoundException.class,
                () -> service.deleteRecord(SESSION_ID, "orders", "r1", USER_ID));

        assertEquals("record not found, id=r1", update.getMessage());
        assertEquals("record not found, id=r1", delete.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dropsFieldsOutsideSchema() {
        when(recordService.update(eq("ds-general"), eq("r1"), any(), eq(USER_ID))).thenReturn(Boolean.TRUE);

        var payload = JsonUtil.toMap(service.updateRecord(SESSION_ID, "orders", "r1",
                "{\"amount\":12,\"unknown\":\"x\"}", USER_ID));

        var data = ArgumentCaptor.forClass(Map.class);
        verify(recordService).update(eq("ds-general"), eq("r1"), data.capture(), eq(USER_ID));
        assertEquals(Set.of("amount"), data.getValue().keySet());
        assertEquals(List.of("amount"), payload.get("updated_fields"));
    }

    @Test
    void rejectsWriteWhenNoFieldMatchesSchema() {
        var error = assertThrows(BadRequestException.class,
                () -> service.insertRecord(SESSION_ID, "orders", "{\"unknown\":\"x\"}", USER_ID));

        assertEquals("none of the provided fields match the dataset schema: [merchant_id, amount]", error.getMessage());
        verify(recordService, never()).insert(any());
    }

    @Test
    void auditsFieldNamesAndSizesOnly() {
        when(recordService.update(eq("ds-general"), eq("r1"), any(), eq(USER_ID))).thenReturn(Boolean.TRUE);

        service.updateRecord(SESSION_ID, "orders", "r1", "{\"amount\":12}", USER_ID);

        var request = ArgumentCaptor.forClass(HubCallAuditService.BeginRequest.class);
        verify(auditService).begin(request.capture());
        var audited = request.getValue();
        assertEquals(HubCallAuditService.KIND_DATASET, audited.kind());
        assertEquals(HubDatasetService.OP_RECORDS_UPDATE, audited.name());
        assertEquals(HubDatasetService.GROUP_RECORDS, audited.group());
        assertEquals("orders", audited.target());
        assertEquals("ds-general", audited.refId());
        assertEquals(SESSION_ID, audited.sessionId());
        assertTrue(audited.argumentsJson().contains("amount"));
        assertFalse(audited.argumentsJson().contains("12"));
        verify(auditService).finish(eq("call-1"), anyLong(), any(), eq(true), any(), any());
    }

    @Test
    void statePayloadMatchesTool() {
        var record = new DatasetRecord();
        record.data = "{\"menu\":\"pizza\",\"business_type\":\"QSR\"}";
        when(recordService.queryBySession("ds-session", SESSION_ID)).thenReturn(Optional.of(record));

        var read = GetSessionStateTool.create(datasetService, recordService, registry()).execute(
                "{\"dataset_id\":\"menu-state\"}", context());
        assertEquals(read.getResult(), service.getState(SESSION_ID, "menu-state", null, USER_ID));

        var projected = GetSessionStateTool.create(datasetService, recordService, registry()).execute(
                "{\"dataset_id\":\"menu-state\",\"fields\":\"business_type\"}", context());
        assertEquals(projected.getResult(), service.getState(SESSION_ID, "menu-state", "business_type", USER_ID));

        var blankProjection = GetSessionStateTool.create(datasetService, recordService, registry()).execute(
                "{\"dataset_id\":\"menu-state\",\"fields\":\"\"}", context());
        assertEquals(blankProjection.getResult(), service.getState(SESSION_ID, "menu-state", "", USER_ID));

        var saved = SetSessionStateTool.create("agent1", datasetService, recordService, registry()).execute(
                "{\"dataset_id\":\"menu-state\",\"data\":{\"menu\":\"pizza\"}}", context());
        assertEquals(saved.getResult(), service.saveState(SESSION_ID, "menu-state", "{\"menu\":\"pizza\"}", USER_ID));

        var updated = UpdateSessionStateTool.create("agent1", datasetService, recordService, registry()).execute(
                "{\"dataset_id\":\"menu-state\",\"data\":{\"business_type\":\"QSR\"}}", context());
        assertEquals(updated.getResult(), service.updateState(SESSION_ID, "menu-state", "{\"business_type\":\"QSR\"}", USER_ID));

        // both surfaces write through the very same call arguments
        verify(recordService, atLeastOnce()).upsertBySession("ds-session", SESSION_ID, "{\"menu\":\"pizza\"}", "agent1", USER_ID);
        verify(recordService, atLeastOnce()).patchBySession("ds-session", SESSION_ID, "{\"business_type\":\"QSR\"}", "agent1", USER_ID);
    }

    @Test
    void recordPayloadMatchesTool() {
        when(recordService.query(any())).thenReturn(new DatasetRecordService.QueryResult(List.of(record()), 1, true));
        when(recordService.update(eq("ds-general"), eq("r1"), any(), eq(USER_ID))).thenReturn(Boolean.TRUE);
        when(recordService.delete("ds-general", "r1")).thenReturn(Boolean.TRUE);

        var queried = QueryDatasetRecordsTool.create(datasetService, recordService, registry()).execute(
                "{\"dataset_id\":\"orders\",\"filter\":\"{\\\"amount\\\":12}\"}", context());
        var hubQuery = service.queryRecords(SESSION_ID, "orders", query("{\"amount\":12}"), USER_ID);
        assertEquals(queried.getResult(), hubQuery);
        assertTrue(hubQuery.contains("warning"));

        var blankFields = QueryDatasetRecordsTool.create(datasetService, recordService, registry()).execute(
                "{\"dataset_id\":\"orders\",\"fields\":\"\"}", context());
        assertEquals(blankFields.getResult(), service.queryRecords(SESSION_ID, "orders",
                new HubDatasetService.RecordQuery(null, "", null, null, null, null), USER_ID));

        var inserted = InsertDatasetRecordTool.create("agent1", null, datasetService, recordService, registry()).execute(
                "{\"dataset_id\":\"orders\",\"data\":{\"merchant_id\":\"M1\",\"amount\":12}}", context());
        assertEquals(inserted.getResult(), service.insertRecord(SESSION_ID, "orders",
                "{\"merchant_id\":\"M1\",\"amount\":12}", USER_ID));

        var updated = UpdateDatasetRecordTool.create(datasetService, recordService, registry()).execute(
                "{\"dataset_id\":\"orders\",\"record_id\":\"r1\",\"data\":{\"amount\":12}}", context());
        assertEquals(updated.getResult(), service.updateRecord(SESSION_ID, "orders", "r1", "{\"amount\":12}", USER_ID));

        var deleted = DeleteDatasetRecordTool.create(datasetService, recordService, registry()).execute(
                "{\"dataset_id\":\"orders\",\"record_id\":\"r1\"}", context());
        assertEquals(deleted.getResult(), service.deleteRecord(SESSION_ID, "orders", "r1", USER_ID));
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

    private HubDatasetService.RecordQuery query(String filter) {
        return new HubDatasetService.RecordQuery(filter, null, null, null, null, null);
    }

    private ExecutionContext context() {
        return ExecutionContext.builder().sessionId(SESSION_ID).userId(USER_ID).build();
    }

    private DatasetAccessRegistry registry() {
        return DatasetAccessRegistry.from(List.of(datasetConfig("ds-session", DatasetPermission.FULL),
                datasetConfig("ds-general", DatasetPermission.FULL)), datasetService);
    }

    private ChatSession session(AgentDatasetConfig... configs) {
        var result = new ChatSession();
        result.id = SESSION_ID;
        result.userId = USER_ID;
        result.agentId = "agent1";
        result.datasetConfig = List.of(configs);
        return result;
    }

    private AgentDatasetConfig datasetConfig(String datasetId, DatasetPermission permission) {
        var config = new AgentDatasetConfig();
        config.datasetId = datasetId;
        config.permission = permission;
        return config;
    }

    private Dataset dataset(String id, String name, DatasetType type, List<String> schema) {
        var dataset = new Dataset();
        dataset.id = id;
        dataset.name = name;
        dataset.type = type;
        dataset.schema = schema == null ? null : schema.stream().map(HubDatasetServiceTest::field).toList();
        return dataset;
    }
}
