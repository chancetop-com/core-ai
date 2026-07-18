package ai.core.media;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author Stephen
 */
public class GoogleAccessTokenProvider {
    private static final List<String> CLOUD_PLATFORM_SCOPE = List.of("https://www.googleapis.com/auth/cloud-platform");

    private final String serviceAccountJson;
    private GoogleCredentials credentials;

    public GoogleAccessTokenProvider(String serviceAccountJson) {
        this.serviceAccountJson = serviceAccountJson;
    }

    public synchronized String accessToken() {
        try {
            if (credentials == null) credentials = loadCredentials();
            credentials.refreshIfExpired();
            var token = credentials.getAccessToken();
            if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
                throw new IllegalStateException("Google application credentials did not provide an access token");
            }
            return token.getTokenValue();
        } catch (IOException e) {
            throw new IllegalStateException("failed to refresh Google access token", e);
        }
    }

    private GoogleCredentials loadCredentials() throws IOException {
        return (serviceAccountJson == null || serviceAccountJson.isBlank()
                ? GoogleCredentials.getApplicationDefault()
                : ServiceAccountCredentials.fromStream(new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))))
                .createScoped(CLOUD_PLATFORM_SCOPE);
    }
}
