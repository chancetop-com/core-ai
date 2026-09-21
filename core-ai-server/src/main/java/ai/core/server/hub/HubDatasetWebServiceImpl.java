package ai.core.server.hub;

import ai.core.api.server.hub.HubDatasetDataRequest;
import ai.core.api.server.hub.HubDatasetListView;
import ai.core.api.server.hub.HubDatasetOpResponse;
import ai.core.api.server.hub.HubDatasetRecordsQuery;
import ai.core.api.server.hub.HubDatasetStateQuery;
import ai.core.api.server.hub.HubDatasetView;
import ai.core.api.server.hub.HubDatasetWebService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.tool.DatasetAccessRegistry;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;
import core.framework.web.exception.UnauthorizedException;

/**
 * Session-anchored dataset access for scripts running outside a sandbox. Every operation is delegated to
 * {@link HubDatasetService}, which owns the rules; this layer only resolves the caller and shapes the response
 * (typed list, {@code {"op","payload"}} for everything else).
 *
 * @author stephen
 */
public class HubDatasetWebServiceImpl implements HubDatasetWebService {
    private static HubDatasetView view(DatasetAccessRegistry.Binding binding) {
        var view = new HubDatasetView();
        view.datasetId = binding.datasetId();
        view.name = binding.name();
        view.type = binding.type().name();
        view.permission = binding.permission().name();
        view.description = binding.description();
        view.schema = DatasetService.schemaViews(binding.schema());
        return view;
    }

    private static HubDatasetOpResponse response(String op, String payload) {
        var response = new HubDatasetOpResponse();
        response.op = op;
        response.payload = payload;
        return response;
    }

    private static String data(HubDatasetDataRequest request) {
        return request == null ? null : request.data;
    }

    @Inject
    HubDatasetService hubDatasetService;
    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired(PermissionCodes.DATASET_VIEW)
    public HubDatasetListView list(String sessionId) {
        var response = new HubDatasetListView();
        response.sessionId = sessionId;
        response.datasets = hubDatasetService.listDatasets(sessionId, userId()).stream().map(HubDatasetWebServiceImpl::view).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.DATASET_VIEW)
    public HubDatasetOpResponse getState(String sessionId, String datasetId, HubDatasetStateQuery query) {
        var fields = query == null ? null : query.fields;
        return response(HubDatasetService.OP_STATE_GET, hubDatasetService.getState(sessionId, datasetId, fields, userId()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.DATASET_VIEW)
    public HubDatasetOpResponse saveState(String sessionId, String datasetId, HubDatasetDataRequest request) {
        return response(HubDatasetService.OP_STATE_SET,
                hubDatasetService.saveState(sessionId, datasetId, data(request), userId()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.DATASET_VIEW)
    public HubDatasetOpResponse patchState(String sessionId, String datasetId, HubDatasetDataRequest request) {
        return response(HubDatasetService.OP_STATE_PATCH,
                hubDatasetService.updateState(sessionId, datasetId, data(request), userId()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.DATASET_VIEW)
    public HubDatasetOpResponse queryRecords(String sessionId, String datasetId, HubDatasetRecordsQuery query) {
        var effective = query == null ? new HubDatasetRecordsQuery() : query;
        var request = new HubDatasetService.RecordQuery(effective.filter, effective.fields, effective.from,
                effective.to, effective.limit, effective.offset);
        return response(HubDatasetService.OP_RECORDS_QUERY, hubDatasetService.queryRecords(sessionId, datasetId, request, userId()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.DATASET_VIEW)
    public HubDatasetOpResponse insertRecord(String sessionId, String datasetId, HubDatasetDataRequest request) {
        return response(HubDatasetService.OP_RECORDS_INSERT,
                hubDatasetService.insertRecord(sessionId, datasetId, data(request), userId()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.DATASET_VIEW)
    public HubDatasetOpResponse updateRecord(String sessionId, String datasetId, String recordId, HubDatasetDataRequest request) {
        return response(HubDatasetService.OP_RECORDS_UPDATE,
                hubDatasetService.updateRecord(sessionId, datasetId, recordId, data(request), userId()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.DATASET_VIEW)
    public HubDatasetOpResponse deleteRecord(String sessionId, String datasetId, String recordId) {
        return response(HubDatasetService.OP_RECORDS_DELETE,
                hubDatasetService.deleteRecord(sessionId, datasetId, recordId, userId()));
    }

    private String userId() {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("authentication required");
        return userId;
    }
}
