package ai.core.server.gateway;

import ai.core.api.server.media.ImageCompareModelView;
import ai.core.api.server.media.ImageCompareRunRequest;
import ai.core.api.server.media.ImageCompareRunResponse;
import ai.core.api.server.media.ListImageCompareModelsResponse;
import ai.core.media.MediaModelParameterHints;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.server.domain.MediaJob;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;

/**
 * Runs one image generation per call for the Generations model-compare view.
 * <p>
 * Deliberately reuses {@link GatewayMediaProvider} instead of talking to an upstream directly: routing,
 * per-model parameter mapping, artifact storage, media job bookkeeping and cost settlement all live in
 * that pipeline, so a comparison result is produced exactly the way a real generation is. Each run is
 * billed to the caller (not to the user whose image is under investigation) and leaves a normal
 * {@code media_jobs} record behind.
 *
 * @author stephen
 */
public class ImageModelCompareService {
    @Inject
    GatewayRoutingEngine routingEngine;
    @Inject
    GatewayMediaProvider gatewayMediaProvider;
    @Inject
    MediaJobService mediaJobService;

    public ListImageCompareModelsResponse models() {
        var response = new ListImageCompareModelsResponse();
        var views = new ArrayList<ImageCompareModelView>();
        for (var hint : routingEngine.mediaModelHints(GatewayEndpointType.IMAGE_GENERATION)) {
            var view = new ImageCompareModelView();
            view.modelId = hint.modelId();
            view.upstreamModel = hint.upstreamModel();
            view.providerName = hint.providerName();
            view.hint = MediaModelParameterHints.imageHint(hint.upstreamModel());
            var config = routingEngine.modelConfig(hint.modelId());
            if (config != null) view.imagePricePerImage = config.imagePricePerImage;
            views.add(view);
        }
        response.models = views;
        return response;
    }

    public ImageCompareRunResponse run(ImageCompareRunRequest request, String userId) {
        var prompt = request.prompt == null ? "" : request.prompt.trim();
        var model = request.model == null ? "" : request.model.trim();
        if (prompt.isEmpty()) throw new BadRequestException("prompt is required");
        if (model.isEmpty()) throw new BadRequestException("model is required");

        // n is fixed at 1: only the first image of a multi-image response is persisted as this job's
        // artifact, so additional samples have to be separate calls to show up in the grid.
        var imageRequest = new ImageGenerationRequest(model, prompt, 1, blankToNull(request.size), blankToNull(request.quality),
                null, null, null, null, null, null, null);
        var owner = new MediaJobOwner(userId, null, null);
        var started = System.currentTimeMillis();
        var generated = gatewayMediaProvider.generateImage(imageRequest, owner);
        var durationMs = System.currentTimeMillis() - started;
        if (generated.mediaId() == null) {
            throw new IllegalStateException("image was generated but not recorded, model=" + model);
        }
        var job = mediaJobService.resolveReference(generated.mediaId(), owner);
        return toView(job, model, durationMs);
    }

    private ImageCompareRunResponse toView(MediaJob job, String model, long durationMs) {
        var view = new ImageCompareRunResponse();
        view.jobId = job.id;
        view.fileId = job.fileId;
        view.fileName = job.fileName;
        view.contentType = job.contentType;
        view.model = model;
        view.resolvedModel = job.resolvedModel;
        view.costUsd = job.costUsd;
        view.costSource = job.costSource;
        view.mediaUnits = job.mediaUnits;
        view.mediaUnitType = job.mediaUnitType;
        view.durationMs = durationMs;
        return view;
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
