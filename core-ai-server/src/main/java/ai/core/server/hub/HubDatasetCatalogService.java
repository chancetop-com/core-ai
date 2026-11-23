package ai.core.server.hub;

import ai.core.server.agent.AgentCallAccessPolicy;
import ai.core.server.dataset.DatasetOpPayloads;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetRecordWriteRules;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetType;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
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
 * Dataset-scope access: GENERAL datasets addressed directly, with no session. Reads are authorized by
 * {@code dataset.view}, writes by {@code dataset.manage} — the caller's own permissions, not an agent's binding.
 * <p>
 * SESSION datasets are refused here: their data only exists per session, so the session-anchored
 * {@link HubDatasetService} remains the only way to reach it. The payload texts are the same
 * {@link DatasetOpPayloads} the agent tools and the session scope produce.
 *
 * @author stephen
 */
public class HubDatasetCatalogService {
    // provenance of rows written outside an agent run: no agent produced them, the hub surface did
    public static final String DIRECT_AGENT_ID = "hub";

    @Inject
    DatasetService datasetService;
    @Inject
    DatasetRecordService datasetRecordService;
    @Inject
    HubCallAuditService auditService;
    @Inject
    AgentCallAccessPolicy accessPolicy;

    public List<Dataset> listDatasets(String query, Integer offset, Integer limit, String userId) {
        var datasets = datasetService.list(query, offset, limit);
        var detail = new LinkedHashMap<String, Object>();
        detail.put("count", datasets.size());
        detail.put("query", query);
        audited(userId, new OpRef(HubDatasetService.OP_DATASETS_LIST, HubDatasetService.GROUP_DATASETS, null, null),
                detail, () -> null);
        return datasets;
    }

    public Dataset getDataset(String datasetRef, String userId) {
        var resolved = resolve(datasetRef);
        var detail = detail(resolved.datasetId());
        detail.put("type", DatasetService.resolveType(resolved.dataset()).name());
        audited(userId, new OpRef(HubDatasetService.OP_DATASETS_GET, HubDatasetService.GROUP_DATASETS,
                resolved.name(), resolved.datasetId()), detail, () -> null);
        return resolved.dataset();
    }

    public String queryRecords(String datasetRef, HubDatasetService.RecordQuery query, String userId) {
        var resolved = resolve(datasetRef);
        requireGeneral(resolved);
        var filter = parseFilter(query.filter());
        var from = parseTime(query.from());
        var to = parseTime(query.to());
        var fields = hasText(query.fields()) ? List.of(query.fields().split(",")) : null;
        var detail = detail(resolved.datasetId());
        detail.put("fields", names(query.fields()));
        detail.put("filter_keys", filter != null ? new ArrayList<>(filter.keySet()) : null);
        detail.put("limit", query.limit());
        detail.put("offset", query.offset());
        return audited(userId, ref(resolved, HubDatasetService.OP_RECORDS_QUERY, HubDatasetService.GROUP_RECORDS),
                detail, () -> {
                    var request = new DatasetRecordService.QueryRequest(resolved.datasetId(), from, to, fields,
                            query.limit(), query.offset(), null, filter);
                    return DatasetOpPayloads.records(resolved.datasetId(), datasetRecordService.query(request));
                });
    }

    public String insertRecord(String datasetRef, String dataText, String userId) {
        var resolved = resolve(datasetRef);
        requireGeneral(resolved);
        var data = normalize(resolved, parseData(dataText), false);
        var detail = detail(resolved.datasetId());
        detail.put("bytes", bytes(JsonUtil.toJson(data)));
        detail.put("fields", new ArrayList<>(data.keySet()));
        return audited(userId, ref(resolved, HubDatasetService.OP_RECORDS_INSERT, HubDatasetService.GROUP_RECORDS),
                detail, () -> {
                    datasetRecordService.insert(new DatasetRecordService.InsertRequest(resolved.datasetId(), DIRECT_AGENT_ID,
                            null, ZonedDateTime.now(), data, userId, userId));
                    return DatasetOpPayloads.recordInserted(resolved.datasetId(), data.keySet());
                });
    }

    public String updateRecord(String datasetRef, String recordId, String dataText, String userId) {
        var resolved = resolve(datasetRef);
        requireGeneral(resolved);
        if (!hasText(recordId)) throw new BadRequestException("record_id is required");
        var data = normalize(resolved, parseData(dataText), true);
        var detail = detail(resolved.datasetId());
        detail.put("record_id", recordId);
        detail.put("bytes", bytes(JsonUtil.toJson(data)));
        detail.put("fields", new ArrayList<>(data.keySet()));
        return audited(userId, ref(resolved, HubDatasetService.OP_RECORDS_UPDATE, HubDatasetService.GROUP_RECORDS),
                detail, () -> {
                    if (!datasetRecordService.update(resolved.datasetId(), recordId, data, userId)) {
                        throw new NotFoundException("record not found, id=" + recordId);
                    }
                    return DatasetOpPayloads.recordUpdated(recordId, resolved.datasetId(), data.keySet());
                });
    }

    public String deleteRecord(String datasetRef, String recordId, String userId) {
        var resolved = resolve(datasetRef);
        requireGeneral(resolved);
        if (!hasText(recordId)) throw new BadRequestException("record_id is required");
        var detail = detail(resolved.datasetId());
        detail.put("record_id", recordId);
        return audited(userId, ref(resolved, HubDatasetService.OP_RECORDS_DELETE, HubDatasetService.GROUP_RECORDS),
                detail, () -> {
                    if (!datasetRecordService.delete(resolved.datasetId(), recordId)) {
                        throw new NotFoundException("record not found, id=" + recordId);
                    }
                    return DatasetOpPayloads.recordDeleted(recordId, resolved.datasetId());
                });
    }

    public String renameField(String datasetRef, String from, String to, String userId) {
        var resolved = resolve(datasetRef);
        requireGeneral(resolved);
        var detail = detail(resolved.datasetId());
        detail.put("from", from);
        detail.put("to", to);
        return audited(userId, ref(resolved, HubDatasetService.OP_FIELDS_RENAME, HubDatasetService.GROUP_FIELDS),
                detail, () -> {
                    try {
                        var result = datasetService.renameField(resolved.datasetId(), from, to, userId);
                        return DatasetOpPayloads.fieldRenamed(resolved.datasetId(), from, to, result.records());
                    } catch (IllegalArgumentException e) {
                        throw new BadRequestException(e.getMessage(), "BAD_REQUEST", e);
                    }
                });
    }

    /**
     * Resolves a dataset ref: the id wins, a name works only when exactly one dataset has it, and an unknown ref is
     * a 404 — there is no binding here that a permission denial would have to hide behind.
     */
    private Resolved resolve(String datasetRef) {
        if (!hasText(datasetRef)) throw new BadRequestException("dataset_id is required");
        var dataset = datasetService.get(datasetRef);
        if (dataset == null) {
            List<Dataset> named = datasetService.listByExactName(datasetRef);
            if (named == null) named = List.of();
            if (named.size() > 1) {
                var ids = named.stream().map(candidate -> candidate.id).sorted().toList();
                throw new BadRequestException("dataset name is ambiguous, pass the dataset id: " + datasetRef
                        + " (candidates: " + String.join(", ", ids) + ")");
            }
            if (!named.isEmpty()) dataset = named.getFirst();
        }
        if (dataset == null) throw new NotFoundException("dataset not found: " + datasetRef);
        return new Resolved(dataset.id, dataset.name, dataset);
    }

    private void requireGeneral(Resolved resolved) {
        if (DatasetService.resolveType(resolved.dataset()) != DatasetType.GENERAL) {
            throw new BadRequestException("session dataset is not accessible via dataset record tools, "
                    + "use get_session_state/set_session_state instead: " + resolved.datasetId());
        }
    }

    private Map<String, Object> normalize(Resolved resolved, Map<String, Object> data, boolean patch) {
        Map<String, Object> normalized;
        try {
            normalized = patch
                    ? DatasetRecordWriteRules.normalizePatch(resolved.dataset, data)
                    : DatasetRecordWriteRules.normalize(resolved.dataset, data);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), "BAD_REQUEST", e);
        }
        if (normalized.isEmpty()) {
            throw new BadRequestException("none of the provided fields match the dataset schema: "
                    + DatasetRecordWriteRules.schemaFieldNames(resolved.dataset));
        }
        return normalized;
    }

    private String audited(String userId, OpRef opRef, Map<String, Object> detail, Supplier<String> body) {
        var callId = auditService.begin(new HubCallAuditService.BeginRequest(UUID.randomUUID().toString(),
                HubCallAuditService.KIND_DATASET, userId, userType(userId), "hub", opRef.target(), opRef.refId(),
                opRef.group(), opRef.op(), JsonUtil.toJson(detail), null, null, null));
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
        return new OpRef(op, group, resolved.name(), resolved.datasetId());
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

    private record Resolved(String datasetId, String name, Dataset dataset) {
    }

    private record OpRef(String op, String group, String target, String refId) {
    }
}
