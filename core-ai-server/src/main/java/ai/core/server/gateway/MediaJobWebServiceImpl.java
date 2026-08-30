package ai.core.server.gateway;

import ai.core.api.server.media.ListMediaJobsRequest;
import ai.core.api.server.media.ListMediaJobsResponse;
import ai.core.api.server.media.MediaJobView;
import ai.core.api.server.media.MediaJobWebService;
import ai.core.server.domain.MediaJob;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import core.framework.inject.Inject;

/**
 * @author Stephen
 */
@PermissionsRequired(PermissionCodes.TRACE_VIEW)
public class MediaJobWebServiceImpl implements MediaJobWebService {
    private static MediaJobView toView(MediaJob job) {
        var view = new MediaJobView();
        view.id = job.id;
        view.userId = job.userId;
        view.providerId = job.providerId;
        view.requestedModel = job.requestedModel;
        view.resolvedModel = job.resolvedModel;
        view.mediaType = job.mediaType == null ? "video" : job.mediaType;
        view.state = job.state;
        view.requestedSeconds = job.requestedSeconds;
        view.mediaUnits = job.mediaUnits;
        view.mediaUnitType = job.mediaUnitType;
        view.creditsConsumed = job.creditsConsumed;
        view.costUsd = job.costUsd;
        view.costSource = job.costSource;
        view.pricingModelId = job.pricingModelId;
        view.progress = job.progress;
        view.error = job.error;
        view.fileId = job.fileId;
        view.fileName = job.fileName;
        view.contentType = job.contentType;
        view.createdAt = job.createdAt;
        view.completedAt = job.completedAt;
        return view;
    }

    @Inject
    MediaJobService mediaJobService;

    @Override
    public ListMediaJobsResponse list(ListMediaJobsRequest request) {
        var offset = request.offset == null ? 0 : Math.max(request.offset, 0);
        var limit = request.limit == null ? 20 : Math.clamp(request.limit, 1, 100);
        var result = mediaJobService.list(offset, limit, request.mediaType, request.costSource, request.userId);
        var response = new ListMediaJobsResponse();
        response.total = result.total();
        response.jobs = result.jobs().stream().map(MediaJobWebServiceImpl::toView).toList();
        return response;
    }
}
