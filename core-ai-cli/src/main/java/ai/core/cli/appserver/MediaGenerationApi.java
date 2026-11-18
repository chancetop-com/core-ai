package ai.core.cli.appserver;

import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageData;
import ai.core.media.domain.VideoGenerationRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-agent media surface for plugin pages: the same configured providers the generate_image /
 * generate_video tools use, but without an agent session, tool approval or model-facing text.
 *
 * <p>Image generation is synchronous. Video generation is asynchronous; the job table is in memory,
 * so an engine restart loses the handle (the upstream task still exists). Files are written under
 * {@code .core-ai/media} so the existing artifact listing and page asset protocol serve them.
 *
 * @author stephen
 */
public class MediaGenerationApi {
    private static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 512L * 1024 * 1024;

    private final Path workspace;
    private final MediaProvider imageProvider;
    private final MediaProvider videoProvider;
    private final String defaultImageModel;
    private final String defaultVideoModel;
    private final MediaSettingsApi settings;
    private final Map<String, VideoJob> jobs = new ConcurrentHashMap<>();

    public MediaGenerationApi(Path workspace, MediaProvider imageProvider, MediaProvider videoProvider,
                              String defaultImageModel, String defaultVideoModel, MediaSettingsApi settings) {
        this.workspace = workspace;
        this.imageProvider = imageProvider;
        this.videoProvider = videoProvider;
        this.defaultImageModel = defaultImageModel;
        this.defaultVideoModel = defaultVideoModel;
        this.settings = settings;
    }

    public ObjectNode models() {
        var node = settings.get();
        node.put("imageAvailable", imageProvider != null);
        node.put("videoAvailable", videoProvider != null);
        return node;
    }

    public ObjectNode generate(ObjectNode params) {
        var kind = Params.optionalText(params, "kind");
        if (kind == null) kind = "image";
        return switch (kind) {
            case "image" -> generateImage(params);
            case "video" -> generateVideo(params);
            default -> throw RpcException.invalidParams("kind must be image or video");
        };
    }

    public ObjectNode job(ObjectNode params) {
        var id = Params.optionalText(params, "id");
        if (id == null) throw RpcException.invalidParams("id is required");
        var job = jobs.get(id);
        if (job == null) throw RpcException.business("JOB_NOT_FOUND", "media job is not known: " + id);
        var owner = Params.optionalText(params, "_owner");
        if (owner != null && !owner.equals(job.owner())) {
            throw RpcException.business("JOB_NOT_FOUND", "media job is not known: " + id);
        }
        return refreshVideoJob(job);
    }

    public ObjectNode jobs(ObjectNode params) {
        var owner = params == null ? null : Params.optionalText(params, "_owner");
        var node = Params.object();
        var array = node.putArray("jobs");
        for (var job : jobs.values()) {
            if (owner == null || owner.equals(job.owner())) array.add(jobNode(job));
        }
        return node;
    }

    private ObjectNode generateImage(ObjectNode params) {
        if (imageProvider == null) throw RpcException.business("NOT_CONFIGURED", "no image media provider is configured");
        var prompt = Params.optionalText(params, "prompt");
        if (prompt == null || prompt.isBlank()) throw RpcException.invalidParams("prompt is required");
        if (params.has("inputImages") || params.has("mask")) {
            throw RpcException.business("UNSUPPORTED", "reference images are not available on plugin pages yet");
        }
        var model = Params.optionalText(params, "model");
        if (model == null) model = effectiveModel("image");
        var request = new ImageGenerationRequest(
                model,
                prompt,
                intOrNull(params, "n"),
                Params.optionalText(params, "size"),
                Params.optionalText(params, "quality"),
                Params.optionalText(params, "outputFormat"),
                intOrNull(params, "outputCompression"),
                Params.optionalText(params, "background"),
                List.of(),
                null,
                Params.optionalText(params, "providerExtra"),
                Params.optionalText(params, "previousInteractionId"));
        try {
            var response = imageProvider.generateImage(request);
            var node = Params.object();
            node.put("kind", "image");
            if (response.data() != null) {
                var files = node.putArray("files");
                for (var image : response.data()) {
                    var saved = saveImage(image, request.outputFormat());
                    var item = files.addObject();
                    item.put("path", saved.relativePath());
                    item.put("name", saved.fileName());
                    item.put("contentType", saved.contentType());
                    item.put("size", saved.size());
                }
            }
            if (response.mediaId() != null) node.put("mediaId", response.mediaId());
            if (response.interactionId() != null) node.put("interactionId", response.interactionId());
            var notes = node.putArray("notes");
            response.notes().forEach(notes::add);
            return node;
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw RpcException.business("MEDIA_GENERATION_FAILED", message(e));
        }
    }

    private ObjectNode generateVideo(ObjectNode params) {
        if (videoProvider == null) throw RpcException.business("NOT_CONFIGURED", "no video media provider is configured");
        var prompt = Params.optionalText(params, "prompt");
        if (prompt == null || prompt.isBlank()) throw RpcException.invalidParams("prompt is required");
        if (params.has("inputReferences")) {
            throw RpcException.business("UNSUPPORTED", "reference media is not available on plugin pages yet");
        }
        var model = Params.optionalText(params, "model");
        if (model == null) model = effectiveModel("video");
        var request = new VideoGenerationRequest(
                model,
                prompt,
                intOrNull(params, "seconds"),
                Params.optionalText(params, "size"),
                List.of(),
                Params.optionalText(params, "providerExtra"),
                Params.optionalText(params, "previousVideoId"));
        try {
            var response = videoProvider.generateVideo(request);
            var job = new VideoJob(response.id(), response.status(), model, prompt,
                    System.currentTimeMillis(), null, null, null, Params.optionalText(params, "_owner"));
            jobs.put(job.id(), job);
            var node = Params.object();
            node.put("kind", "video");
            node.set("job", jobNode(job));
            var notes = node.putArray("notes");
            response.notes().forEach(notes::add);
            return node;
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw RpcException.business("MEDIA_GENERATION_FAILED", message(e));
        }
    }

    private ObjectNode refreshVideoJob(VideoJob job) {
        if (terminal(job.status()) || videoProvider == null) return jobNode(job);
        try {
            var status = videoProvider.getVideoStatus(job.id());
            if (status == null) return jobNode(job);
            var updated = new VideoJob(job.id(), status.status(), job.model(), job.prompt(), job.createdAt(),
                    status.progress(), status.error(), job.relativePath(), job.owner());
            if ("completed".equalsIgnoreCase(status.status()) && updated.relativePath() == null) {
                var saved = saveVideo(job.id());
                updated = new VideoJob(updated.id(), updated.status(), updated.model(), updated.prompt(), updated.createdAt(),
                        updated.progress(), updated.error(), saved.relativePath(), updated.owner());
            }
            jobs.put(job.id(), updated);
            return jobNode(updated);
        } catch (Exception e) {
            var failed = new VideoJob(job.id(), "failed", job.model(), job.prompt(), job.createdAt(),
                    job.progress(), message(e), job.relativePath(), job.owner());
            jobs.put(job.id(), failed);
            return jobNode(failed);
        }
    }

    private ObjectNode jobNode(VideoJob job) {
        var node = Params.object();
        node.put("id", job.id());
        node.put("kind", "video");
        node.put("status", job.status());
        node.put("model", job.model());
        node.put("prompt", job.prompt());
        node.put("createdAt", job.createdAt());
        if (job.progress() != null) node.put("progress", job.progress());
        if (job.error() != null) node.put("error", job.error());
        if (job.relativePath() != null) node.put("path", job.relativePath());
        return node;
    }

    private String effectiveModel(String kind) {
        var configured = "image".equals(kind) ? defaultImageModel : defaultVideoModel;
        if (configured != null && !configured.isBlank()) return configured;
        if (settings == null) return null;
        var info = settings.get();
        return "image".equals(kind)
                ? text(info.path("effectiveImageModel"))
                : text(info.path("effectiveVideoModel"));
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private SavedFile saveImage(ImageData image, String outputFormat) {
        if (image.b64Json() != null) {
            var bytes = decode(image.b64Json());
            var contentType = "jpeg".equalsIgnoreCase(outputFormat) ? "image/jpeg" : "image/png";
            var extension = "jpeg".equalsIgnoreCase(outputFormat) ? "jpeg" : "png";
            return write(bytes, ".core-ai/media/images/image-" + UUID.randomUUID() + "." + extension, contentType);
        }
        if (image.url() == null || image.url().isBlank()) {
            throw RpcException.business("MEDIA_GENERATION_FAILED", "image provider returned no image data");
        }
        var downloaded = MediaFileStore.download(image.url(), MAX_IMAGE_BYTES);
        var extension = MediaFileStore.imageExtension(downloaded.contentType());
        return write(downloaded.bytes(), ".core-ai/media/images/image-" + UUID.randomUUID() + "." + extension,
                downloaded.contentType());
    }

    private SavedFile saveVideo(String videoId) {
        try {
            var bytes = videoProvider.downloadVideo(videoId);
            if (bytes == null || bytes.length == 0) {
                throw RpcException.business("MEDIA_GENERATION_FAILED", "video provider returned no file");
            }
            if (bytes.length > MAX_VIDEO_BYTES) {
                throw RpcException.business("MEDIA_GENERATION_FAILED", "video file is too large");
            }
            return write(bytes, ".core-ai/media/videos/video-" + UUID.randomUUID() + ".mp4", "video/mp4");
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw RpcException.business("MEDIA_GENERATION_FAILED", message(e));
        }
    }

    private SavedFile write(byte[] bytes, String relativePath, String contentType) {
        var target = workspace.resolve(relativePath).normalize();
        if (!target.startsWith(workspace.normalize())) {
            throw RpcException.business("MEDIA_GENERATION_FAILED", "invalid media path");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw RpcException.business("MEDIA_WRITE_FAILED", "failed to save generated media: " + e.getMessage(), e);
        }
        return new SavedFile(relativePath.replace('\\', '/'), target.getFileName().toString(), contentType, bytes.length);
    }

    private byte[] decode(String value) {
        var payload = value;
        var comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma >= 0) payload = payload.substring(comma + 1);
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw RpcException.business("MEDIA_GENERATION_FAILED", "image provider returned invalid base64 data", e);
        }
    }

    private Integer intOrNull(ObjectNode params, String name) {
        var node = params.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isInt() && !node.isLong()) throw RpcException.invalidParams(name + " must be an integer");
        return node.asInt();
    }

    private boolean terminal(String status) {
        return status != null && List.of("completed", "failed", "cancelled", "canceled")
                .contains(status.toLowerCase(Locale.ROOT));
    }

    private String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private record SavedFile(String relativePath, String fileName, String contentType, long size) {
    }

    private record VideoJob(String id, String status, String model, String prompt, long createdAt,
                            Integer progress, String error, String relativePath, String owner) {
    }

    /** Byte source shared with the tests: one bounded HTTP GET and a content-type based extension. */
    static final class MediaFileStore {
        private static final java.net.http.HttpClient CLIENT = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();

        static Downloaded download(String url, long maxBytes) {
            try {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofMinutes(5))
                        .GET()
                        .build();
                var response = CLIENT.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw RpcException.business("MEDIA_GENERATION_FAILED", "image download failed: HTTP " + response.statusCode());
                }
                var contentType = response.headers().firstValue("content-type")
                        .orElse("application/octet-stream").toLowerCase(Locale.ROOT).split(";")[0].trim();
                var bytes = readBounded(response.body(), maxBytes);
                return new Downloaded(bytes, contentType);
            } catch (RpcException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RpcException.business("MEDIA_GENERATION_FAILED", "image download interrupted", e);
            } catch (Exception e) {
                throw RpcException.business("MEDIA_GENERATION_FAILED",
                        "image download failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
            }
        }

        static String imageExtension(String contentType) {
            return switch (contentType == null ? "" : contentType) {
                case "image/jpeg", "image/jpg" -> "jpeg";
                case "image/webp" -> "webp";
                case "image/gif" -> "gif";
                default -> "png";
            };
        }

        private static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
            try (input) {
                var output = new java.io.ByteArrayOutputStream();
                var buffer = new byte[8192];
                long total = 0;
                int read = input.read(buffer);
                while (read != -1) {
                    total += read;
                    if (total > maxBytes) throw RpcException.business("MEDIA_GENERATION_FAILED", "downloaded media is too large");
                    output.write(buffer, 0, read);
                    read = input.read(buffer);
                }
                return output.toByteArray();
            }
        }

        private MediaFileStore() {
        }

        record Downloaded(byte[] bytes, String contentType) {
        }
    }
}
