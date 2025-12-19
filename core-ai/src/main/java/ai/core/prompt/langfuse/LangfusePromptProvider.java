package ai.core.prompt.langfuse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serial;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public class LangfusePromptProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(LangfusePromptProvider.class);

    private final LangfusePromptConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, LangfusePrompt> cache;
    private final boolean cacheEnabled;

    public LangfusePromptProvider(LangfusePromptConfig config) {
        this(config, true);
    }

    public LangfusePromptProvider(LangfusePromptConfig config, boolean cacheEnabled) {
        this.config = config;
        this.cacheEnabled = cacheEnabled;
        this.cache = new HashMap<>();

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
            .build();

        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public LangfusePrompt getPrompt(String name) throws LangfusePromptException {
        return getPrompt(name, null, null);
    }

    public LangfusePrompt getPrompt(String name, Integer version) throws LangfusePromptException {
        return getPrompt(name, version, null);
    }

    public LangfusePrompt getPrompt(String name, Integer version, String label) throws LangfusePromptException {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Prompt name is required");
        }

        // Check cache first
        String cacheKey = buildCacheKey(name, version, label);
        if (cacheEnabled && cache.containsKey(cacheKey)) {
            LOGGER.debug("Returning cached prompt: {}", cacheKey);
            return cache.get(cacheKey);
        }

        // Build query parameters
        Map<String, String> queryParams = new HashMap<>();
//        queryParams.put("name", name);
        if (version != null) {
            queryParams.put("version", String.valueOf(version));
        }
        if (label != null && !label.isEmpty()) {
            queryParams.put("label", label);
        }

        String url = buildUrl(config.getPromptEndpoint() + "/" + name, queryParams);

        try {
            LOGGER.debug("Fetching prompt from Langfuse: {}", url);

            // Build request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .GET();
            if (!url.startsWith("https"))
                requestBuilder.version(HttpClient.Version.HTTP_1_1);

            // Add headers
            config.getHeaders().forEach(requestBuilder::header);
            requestBuilder.header("Content-Type", "application/json");

            HttpRequest request = requestBuilder.build();

            // Send request
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Check response status
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LangfusePrompt prompt = objectMapper.readValue(response.body(), LangfusePrompt.class);

                // Cache the result
                if (cacheEnabled) {
                    cache.put(cacheKey, prompt);
                }

                LOGGER.info("Successfully fetched prompt '{}' (version: {}, type: {})",
                    prompt.getName(), prompt.getVersion(), prompt.getType());
                return prompt;
            } else {
                String errorMessage = String.format("Failed to fetch prompt '%s': HTTP %d - %s",
                    name, response.statusCode(), response.body());
                LOGGER.error(errorMessage);
                throw new LangfusePromptException(errorMessage);
            }
        } catch (IOException | InterruptedException e) {
            String errorMessage = String.format("Error fetching prompt '%s': %s", name, e.getMessage());
            LOGGER.error(errorMessage, e);
            throw new LangfusePromptException(errorMessage, e);
        }
    }

    public LangfusePrompt getPromptByLabel(String name, String label) throws LangfusePromptException {
        return getPrompt(name, null, label);
    }

    public String getPromptContent(String name) throws LangfusePromptException {
        return getPrompt(name).getPromptContent();
    }

    public String getPromptContent(String name, Integer version) throws LangfusePromptException {
        return getPrompt(name, version).getPromptContent();
    }

    public String getPromptContentByLabel(String name, String label) throws LangfusePromptException {
        return getPromptByLabel(name, label).getPromptContent();
    }

    public void clearCache() {
        cache.clear();
        LOGGER.debug("Prompt cache cleared");
    }

    public void removeCachedPrompt(String name, Integer version, String label) {
        String cacheKey = buildCacheKey(name, version, label);
        cache.remove(cacheKey);
        LOGGER.debug("Removed cached prompt: {}", cacheKey);
    }

    private String buildCacheKey(String name, Integer version, String label) {
        var key = new StringBuilder(name);
        if (version != null) {
            key.append(":v").append(version);
        }
        if (label != null && !label.isEmpty()) {
            key.append(':').append(label);
        }
        return key.toString();
    }

    private String buildUrl(String baseUrl, Map<String, String> queryParams) {
        if (queryParams.isEmpty()) {
            return baseUrl;
        }

        var url = new StringBuilder(baseUrl);
        url.append('?');

        boolean first = true;
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (!first) {
                url.append('&');
            }
            url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }

        return url.toString();
    }

    public static class LangfusePromptException extends Exception {
        @Serial
        private static final long serialVersionUID = -753778563265398270L;

        public LangfusePromptException(String message) {
            super(message);
        }

        public LangfusePromptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
