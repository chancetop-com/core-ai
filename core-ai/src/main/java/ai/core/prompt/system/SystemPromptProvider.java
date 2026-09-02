package ai.core.prompt.system;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.Serial;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Fetches prompt content from a core-ai-server SystemPrompt API by name.
 * <p>
 * The server side is {@code GET /api/system-prompts/name/:name} which returns the
 * latest version of the prompt. Prompts are cached in memory after first fetch.
 * <p>
 * Configured via {@code system.prompt.api.*} properties (core-ai standard):
 * <ul>
 *   <li>{@code system.prompt.api.base.url} - core-ai-server base URL (required, use https since core-ng sessions require it)</li>
 *   <li>{@code system.prompt.api.key} - bearer API key for core-ai-server</li>
 *   <li>{@code system.prompt.api.trust.all} - trust any server certificate, e.g. core-ai-server's self-signed cert in-cluster (default false)</li>
 *   <li>{@code system.prompt.timeout.seconds} - request timeout (default 10)</li>
 * </ul>
 *
 * @author stephen
 */
public class SystemPromptProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemPromptProvider.class);

    private final SystemPromptConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> cache;
    private final boolean cacheEnabled;

    public SystemPromptProvider(SystemPromptConfig config) {
        this(config, true);
    }

    public SystemPromptProvider(SystemPromptConfig config, boolean cacheEnabled) {
        this.config = config;
        this.cacheEnabled = cacheEnabled;
        this.cache = new HashMap<>();

        var builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()));
        if (config.isTrustAll()) {
            builder.sslContext(trustAllSSLContext());
        }
        this.httpClient = builder.build();

        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            LOGGER.warn("system.prompt.api.key not configured - prompt fetch will fail if the server requires auth");
        }
    }

    private static SSLContext trustAllSSLContext() {
        try {
            var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }
            }}, new SecureRandom());
            return sslContext;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new Error(e);
        }
    }

    /**
     * Fetch the latest prompt content by name, with in-memory caching.
     */
    public String getPromptContent(String name) throws SystemPromptException {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Prompt name is required");
        }

        var cached = checkCache(name);
        if (cached != null) return cached;

        String url = config.getBaseUrl() + "/api/system-prompts/name/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        var requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
            .GET();
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
        }
        requestBuilder.header("Content-Type", "application/json");
        var request = requestBuilder.build();

        try {
            LOGGER.debug("Fetching system prompt from core-ai-server: {}", url);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return handleResponse(name, response);
        } catch (IOException | InterruptedException e) {
            String errorMessage = String.format("Error fetching prompt '%s': %s", name, e.getMessage());
            LOGGER.error(errorMessage, e);
            throw new SystemPromptException(errorMessage, e);
        }
    }

    private String checkCache(String name) {
        if (!cacheEnabled) return null;
        String cached = cache.get(name);
        if (cached != null) LOGGER.debug("Returning cached system prompt: {}", name);
        return cached;
    }

    private String handleResponse(String name, HttpResponse<String> response) throws SystemPromptException, IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorMessage = String.format("Failed to fetch prompt '%s': HTTP %d - %s", name, response.statusCode(), response.body());
            LOGGER.error(errorMessage);
            throw new SystemPromptException(errorMessage);
        }
        var body = objectMapper.readValue(response.body(), SystemPromptResponse.class);
        String content = body != null ? body.content : null;
        if (content == null || content.isBlank()) {
            throw new SystemPromptException("Prompt content is empty: " + name);
        }
        if (cacheEnabled) cache.put(name, content);
        LOGGER.debug("Successfully fetched system prompt '{}' (length: {})", name, content.length());
        return content;
    }

    public void clearCache() {
        cache.clear();
        LOGGER.debug("System prompt cache cleared");
    }

    /**
     * Minimal projection of the SystemPromptView JSON returned by core-ai-server.
     */
    private static final class SystemPromptResponse {
        public String name;
        public String content;
    }

    public static class SystemPromptException extends Exception {
        @Serial
        private static final long serialVersionUID = 3985716413853906634L;

        public SystemPromptException(String message) {
            super(message);
        }

        public SystemPromptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
