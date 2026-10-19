package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.internal.http.PatchedHTTPClientBuilder;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.web.exception.BadRequestException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Persists generated image bytes or URL results through the execution context's output sink (the desktop
 * artifact panel) or the workspace fallback. URL results are downloaded right away because upstream links
 * expire; a failed download keeps the URL instead of failing the generation.
 *
 * @author stephen
 */
final class GeneratedImageFiles {
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final HTTPClient CLIENT = new PatchedHTTPClientBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .timeout(Duration.ofMinutes(5))
            .trustAll()
            .build();

    static boolean hasSink(ExecutionContext context) {
        return context.getCustomVariable(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY,
                GenerateImageTool.ImageOutputSink.class) != null;
    }

    static String saveBase64(ExecutionContext context, String base64, String outputFormat) {
        var extension = "jpeg".equalsIgnoreCase(outputFormat) ? "jpeg" : "png";
        return save(context, "image-" + UUID.randomUUID() + "." + extension, "image/" + extension, decode(base64));
    }

    /** Downloads and stores a URL result; null when the download fails and the caller should keep the URL. */
    static String saveUrl(ExecutionContext context, String url) {
        DownloadedImage loaded;
        try {
            loaded = download(url);
        } catch (RuntimeException e) {
            return null;
        }
        if (loaded == null) return null;
        var extension = extension(loaded.contentType());
        return save(context, "image-" + UUID.randomUUID() + "." + extension, loaded.contentType(), loaded.data());
    }

    private static String save(ExecutionContext context, String fileName, String contentType, byte[] bytes) {
        var sink = context.getCustomVariable(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY,
                GenerateImageTool.ImageOutputSink.class);
        if (sink != null) return sink.save(fileName, contentType, bytes);

        var workspace = context.getCustomVariables().get("workspace");
        if (!(workspace instanceof String workspacePath) || workspacePath.isBlank()) {
            throw new BadRequestException("workspace is required to save the generated image");
        }
        var outputDirectory = Path.of(workspacePath).resolve(".core-ai").resolve("media").resolve("images");
        var outputPath = outputDirectory.resolve(fileName);
        try {
            Files.createDirectories(outputDirectory);
            Files.write(outputPath, bytes);
            return outputPath.toString();
        } catch (IOException e) {
            throw new RuntimeException("failed to save generated image: " + outputPath, e);
        }
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("image provider returned invalid base64 data", "BAD_REQUEST", e);
        }
    }

    private static DownloadedImage download(String url) {
        var target = url;
        for (var hop = 0; ; hop++) {
            var response = CLIENT.execute(new HTTPRequest(HTTPMethod.GET, target));
            if (response.statusCode < 300 || response.statusCode >= 400) {
                return validImage(response);
            }
            if (hop >= MAX_REDIRECTS) return null;
            target = redirectTarget(target, header(response, "Location"));
            if (target == null) return null;
        }
    }

    private static DownloadedImage validImage(HTTPResponse response) {
        if (response.statusCode < 200 || response.statusCode >= 300) return null;
        if (response.body == null || response.body.length == 0) return null;
        if (response.body.length > MAX_DOWNLOAD_BYTES) return null;
        var contentType = header(response, "Content-Type");
        if (contentType == null) return null;
        contentType = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!contentType.startsWith("image/")) return null;
        return new DownloadedImage(response.body, contentType);
    }

    private static String redirectTarget(String from, String location) {
        if (location == null || location.isBlank()) return null;
        try {
            return URI.create(from).resolve(location).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // response headers preserve the origin's casing, so never match them case-sensitively
    private static String header(HTTPResponse response, String name) {
        if (response.headers == null) return null;
        for (var entry : response.headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg", "image/jpg" -> "jpeg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    private GeneratedImageFiles() {
    }

    private record DownloadedImage(byte[] data, String contentType) {
    }
}
