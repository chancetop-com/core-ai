package ai.core.server.hub;

import ai.core.server.agent.AgentCallAccessPolicy;
import ai.core.server.dataset.DatasetOpPayloads;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetRecordWriteRules;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.tool.DatasetAccessRegistry;
import ai.core.server.dataset.tool.QueryDatasetRecordsTool;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetType;
import ai.core.server.session.SessionRegistry;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Dataset access for scripts running outside the agent loop, addressed by session. A local script reaches it
 * through the hub endpoints, a sandbox script through the dataset section of the sandbox catalog; both run the
 * same operations the agent tools run, over the session's dataset bindings, and return the very same payload
 * text so a script cannot tell which surface answered it.
 *
 * @author stephen
 */
public class HubDatasetService {
    public static final String OP_DATASETS_LIST = "datasets.list";
    public static final String OP_STATE_GET = "state.get";
    public static final String OP_STATE_SET = "state.set";
    public static final String OP_STATE_PATCH = "state.patch";
    public static final String OP_RECORDS_QUERY = "records.query";
    public static final String OP_RECORDS_INSERT = "records.insert";
    public static final String OP_RECORDS_UPDATE = "records.update";
    public static final String OP_RECORDS_DELETE = "records.delete";

    public static final String GROUP_DATASETS = "datasets";
    public static final String GROUP_STATE = "state";
    public static final String GROUP_RECORDS = "records";

    @Inject
    SessionRegistry sessionRegistry;
    @Inject
    DatasetService datasetService;
    @Inject
    DatasetRecordService datasetRecordService;
    @Inject
    HubCallAuditService auditService;
    @Inject
    AgentCallAccessPolicy accessPolicy;

    public List<DatasetAccessRegistry.Binding> listDatasets(String sessionId, String userId) {
        var session = sessionRegistry.requireAccessible(sessionId, userId);
        var bindings = DatasetAccessRegistry.from(session.datasetConfig, datasetService).bindings(datasetService);
        var detail = new LinkedHashMap<String, Object>();
        detail.put("count", bindings.size());
        // the typed list is assembled by the web layer; only the call itself is audited here
        audited(session, userId, new OpRef(OP_DATASETS_LIST, GROUP_DATASETS, null, null), detail, () -> null);
        return bindings;
    }

    public String getState(String sessionId, String datasetRef, String fields, String userId) {
        var session = sessionRegistry.requireAccessible(sessionId, userId);
        var resolved = resolve(session, datasetRef, Access.READ);
        requireSessionDataset(resolved, datasetRef);
        var detail = detail(resolved.datasetId());
        detail.put("fields", names(fields));
        return audited(session, userId, ref(resolved, OP_STATE_GET, GROUP_STATE), detail, () -> {
            var record = datasetRecordService.queryBySession(resolved.datasetId, sessionId).orElse(null);
            Map<String, Object> state = null;
            if (record != null) {
                state = JsonUtil.toMap(record.data);
                if (hasText(fields)) state = DatasetOpPayloads.selectStateFields(state, fields);
            }
            return DatasetOpPayloads.stateRead(state, resolved.datasetId, sessionId);
        });
    }

    public String saveState(String sessionId, String datasetRef, String dataText, String userId) {
        var session = sessionRegistry.requireAccessible(sessionId, userId);
        var resolved = resolve(session, datasetRef, Access.WRITE);
        requireSessionDataset(resolved, datasetRef);
        var data = parseData(dataText);
        var dataJson = JsonUtil.toJson(data);
        var size = bytes(dataJson);
        if (size > DatasetRecordService.MAX_STATE_BYTES) {
            throw new BadRequestException("state too large, max " + (DatasetRecordService.MAX_STATE_BYTES / 1024) + " KB");
        }
        var detail = detail(resolved.datasetId);
        detail.put("bytes", size);
        return audited(session, userId, ref(resolved, OP_STATE_SET, GROUP_STATE), detail, () -> {
            datasetRecordService.upsertBySession(resolved.datasetId, sessionId, dataJson, session.agentId, userId);
            return DatasetOpPayloads.stateSaved(resolved.datasetId, sessionId);
        });
    }

    public String updateState(String sessionId, String datasetRef, String dataText, String userId) {
        var session = sessionRegistry.requireAccessible(sessionId, userId);
        var resolved = resolve(session, datasetRef, Access.WRITE);
        requireSessionDataset(resolved, datasetRef);
        var data = parseData(dataText);
        var detail = detail(resolved.datasetId);
        detail.put("bytes", bytes(JsonUtil.toJson(data)));
        detail.put("fields", new ArrayList<>(data.keySet()));
        return audited(session, userId, ref(resolved, OP_STATE_PATCH, GROUP_STATE), detail, () -> {
            try {
                datasetRecordService.patchBySession(resolved.datasetId, sessionId, JsonUtil.toJson(data), session.agentId, userId);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(e.getMessage(), "BAD_REQUEST", e);
            }
            return DatasetOpPayloads.stateUpdated(resolved.datasetId, sessionId, data.keySet());
        });
    }

    public String queryRecords(String sessionId, String datasetRef, RecordQuery query, String userId) {
        var session = sessionRegistry.requireAccessible(sessionId, userId);
        var resolved = resolve(session, datasetRef, Access.READ);
        requireRecordDataset(resolved);
        var filter = parseFilter(query.filter());
        var from = parseTime(query.from());
        var to = parseTime(query.to());
        var fields = hasText(query.fields()) ? List.of(query.fields().split(",")) : null;
        var detail = detail(resolved.datasetId);
        detail.put("fields", names(query.fields()));
        detail.put("filter_keys", filter != null ? new ArrayList<>(filter.keySet()) : null);
        detail.put("limit", query.limit());
        detail.put("offset", query.offset());
        return audited(session, userId, ref(resolved, OP_RECORDS_QUERY, GROUP_RECORDS), detail, () -> {
            var request = new DatasetRecordService.QueryRequest(resolved.datasetId, from, to, fields,
                    query.limit(), query.offset(), null, filter);
            return DatasetOpPayloads.records(resolved.datasetId, datasetRecordService.query(request));
        });
    }

    public String insertRecord(String sessionId, String datasetRef, String dataText, String userId) {
        var session = sessionRegistry.requireAccessible(sessionId, userId);
        var resolved = resolve(session, datasetRef, Access.WRITE);
        requireRecordDataset(resolved);
        var data = filterToSchema(resolved, parseData(dataText));
        var detail = detail(resolved.datasetId);
        detail.put("bytes", bytes(JsonUtil.toJson(data)));
        detail.put("fields", new ArrayList<>(data.keySet()));
        return audited(session, userId, ref(resolved, OP_RECORDS_INSERT, GROUP_RECORDS), detail, () -> {
            var request = new DatasetRecordService.InsertRequest(resolved.datasetId, session.agentId, sessionId,
                    ZonedDateTime.now(), data, userId, userId);
            datasetRecordService.insert(request);
            return DatasetOpPayloads.recordInserted(resolved.datasetId, data.keySet());
        });
    }

    public String updateRecord(String sessionId, String datasetRef, String recordId, String dataText, String userId) {
        var session = sessionRegistry.requireAccessible(sessionId, userId);
        var resolved = resolve(session, datasetRef, Access.WRITE);
        requireRecordDataset(resolved);
        if (!hasText(recordId)) throw new BadRequestException("record_id is required");
        var data = filterToSchema(resolved, parseData(dataText));
        var detail = detail(resolved.datasetId);
        detail.put("record_id", recordId);
        detail.put("bytes", bytes(JsonUtil.toJson(data)));
        detail.put("fields", new ArrayList<>(data.keySet()));
        return audited(session, userId, ref(resolved, OP_RECORDS_UPDATE, GROUP_RECORDS), detail, () -> {
            if (!datasetRecordService.update(resolved.datasetId, recordId, data, userId)) {
                throw new NotFoundException("record not found, id=" + recordId);
            }
            return DatasetOpPayloads.recordUpdated(recordId, resolved.datasetId, data.keySet());
        });
    }

    public String deleteRecord(String sessionId, String datasetRef, String recordId, String userId) {
        var session = sessionRegistry.requireAccessible(sessionId, userId);
        var resolved = resolve(session, datasetRef, Access.DELETE);
        requireRecordDataset(resolved);
        if (!hasText(recordId)) throw new BadRequestException("record_id is required");
        var detail = detail(resolved.datasetId);
        detail.put("record_id", recordId);
        return audited(session, userId, ref(resolved, OP_RECORDS_DELETE, GROUP_RECORDS), detail, () -> {
            if (!datasetRecordService.delete(resolved.datasetId, recordId)) {
                throw new NotFoundException("record not found, id=" + recordId);
            }
            return DatasetOpPayloads.recordDeleted(recordId, resolved.datasetId);
        });
    }

    /**
     * Resolves the ref a script used to one allowed dataset, in the order the tools use: ambiguity first (a name
     * bound more than once fails closed), then the permission the session holds, then the dataset itself. An
     * unresolvable ref is reported as a permission failure so a script cannot probe for dataset ids it may not see.
     */
    private Resolved resolve(ChatSession session, String datasetRef, Access access) {
        if (!hasText(datasetRef)) throw new BadRequestException("dataset_id is required");
        var registry = DatasetAccessRegistry.from(session.datasetConfig, datasetService);
        var ambiguous = registry.ambiguousMessage(datasetRef);
        if (ambiguous != null) throw new BadRequestException(ambiguous);
        var datasetId = registry.resolveId(datasetRef);
        if (datasetId == null || !access.allows(registry, datasetRef)) {
            throw new ForbiddenException(access.denial(datasetRef));
        }
        var dataset = datasetService.get(datasetId);
        if (dataset == null) throw new NotFoundException("dataset not found: " + datasetRef);
        return new Resolved(datasetId, dataset.name, dataset);
    }

    private void requireSessionDataset(Resolved resolved, String datasetRef) {
        if (DatasetService.resolveType(resolved.dataset) != DatasetType.SESSION) {
            throw new BadRequestException("not a session dataset, use dataset record tools instead: " + datasetRef);
        }
    }

    private void requireRecordDataset(Resolved resolved) {
        var sessionError = QueryDatasetRecordsTool.sessionDatasetAccessError(datasetService, resolved.datasetId);
        if (sessionError != null) throw new BadRequestException(sessionError);
    }

    private Map<String, Object> filterToSchema(Resolved resolved, Map<String, Object> data) {
        var filtered = DatasetRecordWriteRules.filterToSchema(resolved.dataset, data);
        if (filtered.isEmpty()) {
            throw new BadRequestException("none of the provided fields match the dataset schema: "
                    + DatasetRecordWriteRules.schemaFieldNames(resolved.dataset));
        }
        return filtered;
    }

    private String audited(ChatSession session, String userId, OpRef opRef, Map<String, Object> detail,
                           Supplier<String> body) {
        var callId = auditService.begin(new HubCallAuditService.BeginRequest(UUID.randomUUID().toString(),
                HubCallAuditService.KIND_DATASET, userId, userType(userId), "hub", opRef.target(), opRef.refId(),
                opRef.group(), opRef.op(), JsonUtil.toJson(detail), null, null, session.id));
        var startedAt = System.currentTimeMillis();
        String payload;
        try {
            payload = body.get();
        } catch (RuntimeException | Error e) {
            auditService.finish(callId, System.currentTimeMillis() - startedAt, null, false, null, e.getMessage());
            throw e;
        }
        auditService.finish(callId, System.currentTimeMillis() - startedAt, payload, true, null, null);
        return payload;
    }

    private Map<String, Object> detail(String datasetId) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("dataset_id", datasetId);
        return detail;
    }

    private OpRef ref(Resolved resolved, String op, String group) {
        return new OpRef(op, group, resolved.name, resolved.datasetId);
    }

    private Map<String, Object> parseData(String dataText) {
        try {
            return DatasetRecordService.parseData(dataText);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private Map<String, Object> parseFilter(String filterText) {
        try {
            return DatasetRecordService.parseFilter(filterText);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private ZonedDateTime parseTime(String time) {
        if (!hasText(time)) return null;
        try {
            return ZonedDateTime.parse(time, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("invalid date format, use ISO 8601: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private List<String> names(String csv) {
        if (!hasText(csv)) return null;
        var list = new ArrayList<String>();
        for (var name : csv.split(",")) {
            if (!name.isBlank()) list.add(name.trim());
        }
        return list;
    }

    private int bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }

    private String userType(String userId) {
        return accessPolicy.isApiUser(userId) ? "api" : "internal";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** The caller-supplied ref is echoed in the denial so a script sees what it asked for, never what exists. */
    private enum Access {
        READ(""),
        WRITE("write "),
        DELETE("delete ");

        private final String prefix;

        Access(String prefix) {
            this.prefix = prefix;
        }

        String denial(String datasetRef) {
            return prefix + "access denied to dataset: " + datasetRef;
        }

        boolean allows(DatasetAccessRegistry registry, String datasetRef) {
            return switch (this) {
                case WRITE -> registry.isWritable(datasetRef);
                case DELETE -> registry.isDeletable(datasetRef);
                default -> true;
            };
        }
    }

    private record Resolved(String datasetId, String name, Dataset dataset) {
    }

    private record OpRef(String op, String group, String target, String refId) {
    }

    public record RecordQuery(String filter, String fields, String from, String to, Integer limit, Integer offset) {
    }
}
