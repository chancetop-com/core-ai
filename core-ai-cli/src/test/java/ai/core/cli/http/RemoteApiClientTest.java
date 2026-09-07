package ai.core.cli.http;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RemoteApiClientTest {
    private static final String[] URLS = {"https://localhost:8443", "https://127.0.0.1:8443", "https://[::1]:8443", "https://example.com"};

    @Test
    void defaultKeepsCertificateVerificationForAnyHost() throws Exception {
        for (var url : URLS) {
            var client = new RemoteApiClient(url, "key");
            assertSame(SSLContext.getDefault(), client.httpClient().sslContext(), url);
        }
    }

    @Test
    void insecureFlagDisablesVerificationForAnyHost() throws Exception {
        for (var url : URLS) {
            var client = new RemoteApiClient(url, "key", null, Map.of(), true);
            assertNotEquals(SSLContext.getDefault(), client.httpClient().sslContext(), url);
        }
    }
}
