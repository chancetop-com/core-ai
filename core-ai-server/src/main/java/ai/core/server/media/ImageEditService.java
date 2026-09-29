package ai.core.server.media;

import ai.core.api.server.media.ImageEditModelListResponse;
import ai.core.api.server.media.ImageEditModelView;
import ai.core.api.server.media.ImageEditRequest;
import ai.core.api.server.media.ImageEditResponse;
import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.MediaReferenceRole;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.file.FileService;
import ai.core.server.gateway.ContextualMediaProvider;
import ai.core.server.gateway.GatewayEndpointType;
import ai.core.server.gateway.GatewayMediaHandle;
import ai.core.server.gateway.GatewayMediaProvider;
import ai.core.server.gateway.GatewayRoute;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.gateway.MediaJobOwner;
import ai.core.server.gateway.MediaJobService;
import ai.core.server.gateway.MediaProviderCapabilities;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.util.ImageJpegCodec;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Region edit: one painted region, one prompt, straight to a media provider. No agent in the loop - the
 * model, the prompt and the size are all decided by the caller - and no new concept on the platform: the
 * result is an ordinary media job, file record and dashboard cost row.
 * <p>
 * A model that carries a mask receives it as its own field. A model that only has a reference-image channel
 * gets the region painted into the picture instead, and only when the caller explicitly accepted that
 * approximation ({@code maskFallback=annotation}) - silently repainting the whole image would be the one
 * failure this feature must not produce.
 *
 * @author stephen
 */
public class ImageEditService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageEditService.class);
    private static final int MAX_PROMPT_LENGTH = 4000;
    private static final int MAX_ERROR_SUMMARY_LENGTH = 300;
    private static final String ANNOTATION = "annotation";
    private static final String MASK_PROMPT_SUFFIX = "\n\n@source is the image provided; only repaint the transparent area of the mask"
            + " and keep everything else unchanged.";
    private static final String ANNOTATION_PROMPT_SUFFIX = "\n\n@source shows the area to change with an orange marking; apply the change"
            + " only inside that marking, keep everything else unchanged, and do not render the orange marking in the result.";

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean acceptsReferences(MediaProviderCapabilities capabilities) {
        return capabilities.acceptsInlineData() || capabilities.acceptsRemoteUrl();
    }

    private static String summarize(String message) {
        var summary = message.strip();
        var newline = summary.indexOf('\n');
        if (newline > 0) summary = summary.substring(0, newline).strip();
        return summary.length() > MAX_ERROR_SUMMARY_LENGTH ? summary.substring(0, MAX_ERROR_SUMMARY_LENGTH) + "..." : summary;
    }

    /** A raw provider failure must not escape as a 500 carrying the upstream body: this call is user facing. */
    private static BadRequestException generationFailure(RuntimeException e) {
        LOGGER.warn("image edit generation failed", e);
        var message = e.getMessage() == null ? "" : e.getMessage();
        var lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("429") || lower.contains("rate limit") || lower.contains("too many requests")) {
            return new BadRequestException("the image model is busy right now, please try again in a moment", "IMAGE_EDIT_FAILED", e);
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return new BadRequestException("image generation timed out, please try again", "IMAGE_EDIT_FAILED", e);
        }
        return new BadRequestException("image generation failed: " + summarize(message), "IMAGE_EDIT_FAILED", e);
    }

    @Inject
    FileService fileService;
    @Inject
    ObjectStorageServiceResolver storageResolver;
    @Inject
    MediaProvider mediaProvider;
    @Inject
    MediaJobService mediaJobService;
    @Inject
    GatewayRoutingEngine routingEngine;
    @Inject
    SystemSettingsService systemSettingsService;

    public ImageEditModelListResponse models() {
        var views = new ArrayList<ImageEditModelView>();
        for (var hint : routingEngine.mediaModelHints(GatewayEndpointType.IMAGE_EDIT)) {
            var route = routeOrNull(hint.modelId());
            if (route == null) continue;
            views.add(modelView(hint.modelId(), hint.providerName(), route));
        }
        var response = new ImageEditModelListResponse();
        response.models = views;
        response.defaultModelId = defaultModelId(views);
        return response;
    }

    public ImageEditResponse edit(String userId, ImageEditRequest request) {
        var prompt = prompt(request.prompt);
        var sourceBytes = sourceBytes(userId, request);
        var dimensions = ImageJpegCodec.dimensions(sourceBytes);
        if (dimensions == null) throw new BadRequestException("image is not a readable image");
        var edit = resolveEdit(request);
        var sourceImage = SourceImage.read(sourceBytes, dimensions[0], dimensions[1]);
        var mask = MaskImage.decode(request.mask, dimensions[0], dimensions[1]);
        var generationRequest = generationRequest(edit, sourceImage, mask, prompt, request.size);
        var started = System.currentTimeMillis();
        var generation = generate(userId, request.sessionId, generationRequest);
        return response(generation, edit, System.currentTimeMillis() - started);
    }

    /**
     * The image to edit, by file id, by share token, or by a URL of our own object storage: the web chat
     * hands generated images out as /api/public/artifacts/{token}/content, while an uploaded attachment
     * exists only as an object storage blob. Ownership is enforced for the two handle forms.
     */
    private byte[] sourceBytes(String userId, ImageEditRequest request) {
        var fileId = trimToNull(request.sourceFileId);
        var shareToken = trimToNull(request.sourceShareToken);
        var url = trimToNull(request.sourceUrl);
        var provided = (fileId == null ? 0 : 1) + (shareToken == null ? 0 : 1) + (url == null ? 0 : 1);
        if (provided == 0) throw new BadRequestException("sourceFileId, sourceShareToken or sourceUrl is required");
        if (provided > 1) throw new BadRequestException("provide only one of sourceFileId, sourceShareToken, sourceUrl");
        if (fileId != null) return fileService.getBytes(fileService.getOwned(fileId, userId));
        if (shareToken != null) return fileService.getBytes(fileService.getOwned(fileService.getShared(shareToken).id, userId));
        return storedBytes(url);
    }

    private byte[] storedBytes(String url) {
        var location = storageResolver.locate(url);
        var storage = storageResolver.resolve();
        if (location == null || storage == null) throw new BadRequestException("only images hosted by this platform can be edited here");
        try {
            return storage.downloadObject(location.container(), location.blobName());
        } catch (RuntimeException e) {
            throw new BadRequestException("the image could not be read from storage", "IMAGE_EDIT_SOURCE_UNAVAILABLE", e);
        }
    }

    private ImageEditModelView modelView(String modelId, String providerName, GatewayRoute route) {
        var capabilities = MediaProviderCapabilities.of(route.provider());
        var view = new ImageEditModelView();
        view.modelId = modelId;
        view.providerName = providerName;
        var config = routingEngine.modelConfig(modelId);
        view.displayName = config == null ? null : config.displayName;
        view.maskSupported = capabilities.supportsMask();
        view.maskFallback = !capabilities.supportsMask() && acceptsReferences(capabilities) ? ANNOTATION : null;
        return view;
    }

    private String defaultModelId(List<ImageEditModelView> views) {
        var configured = systemSettingsService.imageGenerationModel();
        for (var view : views) {
            if (Boolean.TRUE.equals(view.maskSupported) && view.modelId.equals(configured)) return view.modelId;
        }
        for (var view : views) {
            if (Boolean.TRUE.equals(view.maskSupported)) return view.modelId;
        }
        for (var view : views) {
            if (ANNOTATION.equals(view.maskFallback)) return view.modelId;
        }
        return null;
    }

    private ResolvedEdit resolveEdit(ImageEditRequest request) {
        if (hasText(request.model)) return requestedEdit(request);
        for (var candidate : candidates()) {
            var route = routeOrNull(candidate);
            if (route != null && MediaProviderCapabilities.of(route.provider()).supportsMask()) return new ResolvedEdit(route, true);
        }
        if (ANNOTATION.equals(request.maskFallback)) return annotatedEdit(candidates());
        throw new BadRequestException("no image editing model is available, configure an image model with the image.edits endpoint");
    }

    private ResolvedEdit requestedEdit(ImageEditRequest request) {
        var route = routingEngine.route(request.model, GatewayEndpointType.IMAGE_EDIT);
        var capabilities = MediaProviderCapabilities.of(route.provider());
        if (capabilities.supportsMask()) return new ResolvedEdit(route, true);
        if (ANNOTATION.equals(request.maskFallback) && acceptsReferences(capabilities)) return new ResolvedEdit(route, false);
        throw new BadRequestException("model " + request.model + " cannot apply a region mask"
                + (acceptsReferences(capabilities) ? "; pass maskFallback=annotation to approximate the region with an on-image marking"
                : "; choose a model that supports region edits"));
    }

    private ResolvedEdit annotatedEdit(List<String> candidates) {
        for (var candidate : candidates) {
            var route = routeOrNull(candidate);
            if (route != null && acceptsReferences(MediaProviderCapabilities.of(route.provider()))) return new ResolvedEdit(route, false);
        }
        throw new BadRequestException("no image editing model is available, configure an image model with the image.edits endpoint");
    }

    private List<String> candidates() {
        var candidates = new ArrayList<String>();
        var configured = systemSettingsService.imageGenerationModel();
        if (hasText(configured)) candidates.add(configured);
        for (var hint : routingEngine.mediaModelHints(GatewayEndpointType.IMAGE_EDIT)) {
            if (!candidates.contains(hint.modelId())) candidates.add(hint.modelId());
        }
        return candidates;
    }

    /** A candidate that cannot route is skipped rather than fatal: it is one of several options. */
    private GatewayRoute routeOrNull(String model) {
        try {
            return routingEngine.route(model, GatewayEndpointType.IMAGE_EDIT);
        } catch (BadRequestException e) {
            return null;
        }
    }

    private ImageGenerationRequest generationRequest(ResolvedEdit edit, SourceImage source, MaskImage mask,
                                                     String prompt, String requestedSize) {
        var model = edit.route().model() == null ? edit.route().upstreamModel() : edit.route().model().modelId;
        var size = hasText(requestedSize) ? requestedSize : EditGeometry.snapSize(source.width(), source.height());
        if (edit.maskSupported()) {
            return new ImageGenerationRequest(model, prompt + MASK_PROMPT_SUFFIX, 1, size, null, null, null, null,
                    List.of(imageReference(source.png())), maskReference(mask.png()), null, null);
        }
        return new ImageGenerationRequest(model, prompt + ANNOTATION_PROMPT_SUFFIX, 1, size, null, null, null, null,
                List.of(imageReference(source.annotated(mask))), null, null, null);
    }

    private MediaReference imageReference(byte[] png) {
        return new MediaReference(null, dataUrl(png), null, "source", MediaReferenceRole.SUBJECT, MediaModality.IMAGE);
    }

    // the mask never rides in inputImages: it is resolved and forwarded on its own, so reference trimming
    // cannot drop it and leave the model with an unconstrained repaint
    private MediaReference maskReference(byte[] png) {
        return new MediaReference(null, dataUrl(png), null, null, null, MediaModality.IMAGE);
    }

    private String dataUrl(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    private ImageGenerationResponse generate(String userId, String sessionId, ImageGenerationRequest request) {
        try {
            return provider(userId, sessionId).generateImage(request);
        } catch (BadRequestException e) {
            throw e;
        } catch (RuntimeException e) {
            throw generationFailure(e);
        }
    }

    private MediaProvider provider(String userId, String sessionId) {
        if (mediaProvider instanceof GatewayMediaProvider gateway) {
            return new ContextualMediaProvider(gateway, new MediaJobOwner(userId, sessionId, null));
        }
        return mediaProvider;
    }

    private ImageEditResponse response(ImageGenerationResponse generation, ResolvedEdit edit, long elapsedMs) {
        var view = new ImageEditResponse();
        view.maskMode = edit.maskSupported() ? "mask" : ANNOTATION;
        view.notes = generation.notes();
        view.mediaId = generation.mediaId();
        view.model = edit.route().model() == null ? edit.route().upstreamModel() : edit.route().model().modelId;
        view.elapsedMs = elapsedMs;
        if (hasText(generation.mediaId())) {
            var job = mediaJobService.get(GatewayMediaHandle.decode(generation.mediaId()).jobId());
            view.mediaJobId = job.id;
            view.fileId = job.fileId;
            view.url = job.fileId == null ? null : "/api/files/" + job.fileId + "/content";
            view.costUsd = job.costUsd;
            view.costSource = job.costSource;
            if (hasText(job.resolvedModel)) view.model = job.resolvedModel;
        }
        return view;
    }

    private String prompt(String value) {
        var prompt = value == null ? "" : value.trim();
        if (prompt.isEmpty()) throw new BadRequestException("prompt is required");
        if (prompt.length() > MAX_PROMPT_LENGTH) throw new BadRequestException("prompt is too long, max=" + MAX_PROMPT_LENGTH);
        return prompt;
    }

    private record ResolvedEdit(GatewayRoute route, boolean maskSupported) {
    }
}
