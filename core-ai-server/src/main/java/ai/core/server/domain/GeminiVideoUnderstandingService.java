package ai.core.server.domain;

import ai.core.media.GoogleAccessTokenProvider;
import ai.core.server.gateway.GatewayEndpointType;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.gateway.GatewaySecretProtector;
import ai.core.server.settings.SystemSettingsService;
import ai.core.tool.tools.UnderstandVideoTool;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class GeminiVideoUnderstandingService implements UnderstandVideoTool.VideoUnderstandingService {
    private static String strip(String value) {
        var result = value == null || value.isBlank() ? "https://generativelanguage.googleapis.com" : value;
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @Inject
    GatewayRoutingEngine routingEngine;

    @Inject
    GeminiFileService fileService;

    @Inject
    GatewaySecretProtector secretProtector;

    @Inject
    SystemSettingsService systemSettingsService;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Override
    public UnderstandVideoTool.VideoUnderstandingResult understand(UnderstandVideoTool.AttachmentOwner owner,
                                                                     String referenceId, String effectiveModel,
                                                                     String question) {
        var model = resolveVideoModel(effectiveModel);
        var route = routingEngine.route(model, GatewayEndpointType.CHAT_COMPLETIONS);
        if (!"gemini".equalsIgnoreCase(route.provider().type)) {
            throw new IllegalArgumentException("video understanding requires a Gemini provider; "
                    + "register a Gateway model with supportsVideo=true on a gemini provider");
        }
        var capability = routingEngine.modelConfig(model);
        if (capability != null && !Boolean.TRUE.equals(capability.supportsVideo)) {
            throw new IllegalArgumentException("selected model does not support video understanding: " + model);
        }
        var provider = route.provider();
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        if (apiKey != null && !apiKey.isBlank() && isVertexProvider(provider)) {
            return understandWithVertexApiKey(owner, referenceId, route.upstreamModel(), apiKey, provider, question);
        }
        if (apiKey != null && !apiKey.isBlank()) return understandWithDeveloperApi(owner, referenceId, route.upstreamModel(), apiKey, provider, question);
        return understandWithVertex(owner, referenceId, route.upstreamModel(), provider, question);
    }

    private boolean isVertexProvider(GatewayProviderConfig provider) {
        return provider.mediaProtocol != null && provider.mediaProtocol.startsWith("VERTEX_");
    }

    private UnderstandVideoTool.VideoUnderstandingResult understandWithDeveloperApi(UnderstandVideoTool.AttachmentOwner owner,
                                                                                    String referenceId, String upstreamModel,
                                                                                    String apiKey, GatewayProviderConfig provider,
                                                                                    String question) {
        var baseUrl = provider.baseUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Gemini provider baseUrl is not configured: " + provider.name);
        }
        var files = new GeminiFilesClient(baseUrl, apiKey);
        var resolved = fileService.ensureActive(owner, referenceId, provider.id, upstreamModel, files);
        var mediaPart = Map.<String, Object>of("fileData", Map.of("fileUri", resolved.uri(), "mimeType",
                resolved.contentType() == null || resolved.contentType().isBlank() ? "video/mp4" : resolved.contentType()));
        var generated = generate(strip(baseUrl) + "/v1beta/models/" + upstreamModel + ":generateContent",
                "x-goog-api-key", apiKey, mediaPart, question);
        return new UnderstandVideoTool.VideoUnderstandingResult(generated.answer(), upstreamModel,
                resolved.cacheHit() ? "hit" : "miss", generated.promptTokens(), generated.completionTokens(), generated.totalTokens());
    }

    private UnderstandVideoTool.VideoUnderstandingResult understandWithVertex(UnderstandVideoTool.AttachmentOwner owner,
                                                                              String referenceId, String upstreamModel,
                                                                              GatewayProviderConfig provider,
                                                                              String question) {
        if (provider.vertexProjectId == null || provider.vertexProjectId.isBlank()
                || provider.vertexLocation == null || provider.vertexLocation.isBlank()) {
            throw new IllegalArgumentException("Vertex video understanding requires vertexProjectId and vertexLocation on provider: " + provider.name);
        }
        if (provider.vertexGcsBucket == null || provider.vertexGcsBucket.isBlank()) {
            throw new IllegalArgumentException("Vertex video understanding requires a GCS bucket (vertexGcsBucket) on provider: " + provider.name);
        }
        var credentials = secretProtector.unprotect(provider.googleCredentialsEncrypted);
        if (credentials == null || credentials.isBlank()) {
            throw new IllegalArgumentException("Vertex video understanding requires googleCredentialsJson (service account) on provider: " + provider.name);
        }
        var token = new GoogleAccessTokenProvider("GOOGLE_SERVICE_ACCOUNT_JSON".equals(provider.mediaAuthType) ? credentials : null).accessToken();
        var files = new GeminiFilesClient(provider.vertexGcsBucket, token, true);
        var resolved = fileService.ensureActive(owner, referenceId, provider.id, upstreamModel, files);
        var url = strip(provider.baseUrl) + "/projects/" + provider.vertexProjectId + "/locations/" + provider.vertexLocation
                + "/publishers/google/models/" + upstreamModel + ":generateContent";
        var mediaPart = Map.<String, Object>of("fileData", Map.of("fileUri", resolved.uri(), "mimeType",
                resolved.contentType() == null || resolved.contentType().isBlank() ? "video/mp4" : resolved.contentType()));
        var generated = generate(url, "Authorization", "Bearer " + token, mediaPart, question);
        return new UnderstandVideoTool.VideoUnderstandingResult(generated.answer(), upstreamModel,
                resolved.cacheHit() ? "hit" : "miss", generated.promptTokens(), generated.completionTokens(), generated.totalTokens());
    }

    private UnderstandVideoTool.VideoUnderstandingResult understandWithVertexApiKey(UnderstandVideoTool.AttachmentOwner owner,
                                                                                      String referenceId, String upstreamModel,
                                                                                      String apiKey, GatewayProviderConfig provider,
                                                                                      String question) {
        var video = fileService.loadInlineVideo(owner, referenceId);
        var mediaPart = Map.<String, Object>of("inlineData", Map.of("mimeType",
                video.contentType() == null || video.contentType().isBlank() ? "video/mp4" : video.contentType(),
                "data", video.base64Data()));
        var url = strip(provider.baseUrl) + "/publishers/google/models/" + upstreamModel + ":generateContent";
        var generated = generate(url, "x-goog-api-key", apiKey, mediaPart, question);
        return new UnderstandVideoTool.VideoUnderstandingResult(generated.answer(), upstreamModel,
                "miss", generated.promptTokens(), generated.completionTokens(), generated.totalTokens());
    }

    private String resolveVideoModel(String effectiveModel) {
        if (effectiveModel != null && !effectiveModel.isBlank() && isGeminiVideoModel(effectiveModel)) {
            return effectiveModel;
        }
        var configured = systemSettingsService.videoUnderstandingModel();
        if (configured != null && isGeminiVideoModel(configured)) return configured;
        for (var view : routingEngine.availableModels()) {
            if (isGeminiVideoModel(view.modelId)) return view.modelId;
        }
        return effectiveModel;
    }

    private boolean isGeminiVideoModel(String modelId) {
        var config = routingEngine.modelConfig(modelId);
        if (config == null || !Boolean.TRUE.equals(config.supportsVideo)) return false;
        try {
            return "gemini".equalsIgnoreCase(routingEngine.provider(config.providerId).type);
        } catch (core.framework.web.exception.BadRequestException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private GeneratedAnswer generate(String url, String authHeader, String authValue, Map<String, Object> mediaPart, String question) {
        var body = Map.of("contents", List.of(Map.of("role", "user", "parts", List.of(
                mediaPart,
                Map.of("text", question)))));
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .header(authHeader, authValue)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Gemini video understanding failed: HTTP " + response.statusCode() + ": "
                        + truncate(response.body(), 300));
            }
            var root = (Map<String, Object>) JsonUtil.fromJson(Map.class, response.body());
            record UsageCounts(int prompt, int completion, int total) { }
            var usage = (Map<String, Object>) root.get("usageMetadata");
            var usageCounts = usage == null ? new UsageCounts(0, 0, 0) : new UsageCounts(
                    number(usage.get("promptTokenCount")), number(usage.get("candidatesTokenCount")), number(usage.get("totalTokenCount")));
            var candidates = (List<Map<String, Object>>) root.get("candidates");
            if (candidates == null || candidates.isEmpty()) throw new IllegalStateException("Gemini returned no candidates");
            var content = (Map<String, Object>) candidates.getFirst().get("content");
            var parts = (List<Map<String, Object>>) content.get("parts");
            var answer = parts.stream().map(part -> String.valueOf(part.getOrDefault("text", ""))).filter(text -> !text.isBlank()).reduce("", (a, b) -> a + b);
            return new GeneratedAnswer(answer, usageCounts.prompt(), usageCounts.completion(), usageCounts.total());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gemini video understanding request failed", e);
        } catch (IOException e) {
            throw new RuntimeException("Gemini video understanding request failed", e);
        }
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record GeneratedAnswer(String answer, long promptTokens, long completionTokens, long totalTokens) { }
}
