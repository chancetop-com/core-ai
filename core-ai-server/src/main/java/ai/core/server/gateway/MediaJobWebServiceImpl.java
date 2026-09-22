package ai.core.server.gateway;

import ai.core.api.server.media.ImageCompareRunRequest;
import ai.core.api.server.media.ImageCompareRunResponse;
import ai.core.api.server.media.ListImageCompareModelsResponse;
import ai.core.api.server.media.ListMediaJobsRequest;
import ai.core.api.server.media.ListMediaJobsResponse;
import ai.core.api.server.media.MediaJobView;
import ai.core.api.server.media.MediaJobWebService;
import ai.core.server.domain.MediaJob;
import ai.core.server.domain.User;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * @author Stephen
 */
@PermissionsRequired(PermissionCodes.TRACE_VIEW)
public class MediaJobWebServiceImpl implements MediaJobWebService {
    private static MediaJobView toView(MediaJob job, Map<String, String> userNames) {
        var view = new MediaJobView();
        view.id = job.id;
        view.userId = job.userId;
        view.userName = job.userId == null ? null : userNames.get(job.userId);
        view.providerId = job.providerId;
        view.requestedModel = job.requestedModel;
        view.resolvedModel = job.resolvedModel;
        view.prompt = job.prompt;
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
    @Inject
    ImageModelCompareService imageModelCompareService;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    WebContext webContext;

    @Override
    public ListMediaJobsResponse list(ListMediaJobsRequest request) {
        var offset = request.offset == null ? 0 : Math.max(request.offset, 0);
        var limit = request.limit == null ? 20 : Math.clamp(request.limit, 1, 100);
        var result = mediaJobService.list(offset, limit, request.mediaType, request.costSource, request.userId);
        var userNames = resolveUserNames(result.jobs());
        var response = new ListMediaJobsResponse();
        response.total = result.total();
        response.jobs = result.jobs().stream().map(job -> toView(job, userNames)).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.MEDIA_COMPARE)
    public ListImageCompareModelsResponse compareModels() {
        return imageModelCompareService.models();
    }

    @Override
    @PermissionsRequired(PermissionCodes.MEDIA_COMPARE)
    public ImageCompareRunResponse compare(ImageCompareRunRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("image_compare_model", request.model);
        return imageModelCompareService.run(request, userId);
    }

    // batch resolve owner display names so the list can show who generated what without an N+1 query
    private Map<String, String> resolveUserNames(List<MediaJob> jobs) {
        var userIds = new HashSet<String>();
        for (var job : jobs) {
            if (job.userId != null && !job.userId.isBlank()) userIds.add(job.userId);
        }
        if (userIds.isEmpty()) return Map.of();
        var names = new HashMap<String, String>();
        for (var user : userCollection.find(Filters.in("_id", userIds))) {
            names.put(user.id, user.name);
        }
        return names;
    }
}
