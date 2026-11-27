package ai.core.mcp.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class McpSecretExpanderTest {
    private static final String SECRET = "SUPER_SECRET_VALUE_123";

    @AfterEach
    void tearDown() {
        McpLogSanitizer.clearSecrets();
    }

    @Test
    void expandsPlaceholdersInEnvAndHeadersOnly() {
        Map<String, Object> config = Map.of(
                "url", "https://mcp.example.com/mcp",
                "headers", Map.of("Authorization", "Bearer ${env:TOKEN}"),
                "env", Map.of("API_KEY", "${env:TOKEN}")
        );
        var expanded = McpSecretExpander.expand("demo", config, Map.of("TOKEN", SECRET));
        @SuppressWarnings("unchecked")
        var headers = (Map<String, String>) expanded.get("headers");
        @SuppressWarnings("unchecked")
        var env = (Map<String, String>) expanded.get("env");

        assertEquals("Bearer " + SECRET, headers.get("Authorization"));
        assertEquals(SECRET, env.get("API_KEY"));
    }

    @Test
    void rejectsPlaceholderInUrlEndpointCommandAndArgsWithoutEchoingValue() {
        var cases = List.<Map.Entry<String, Map<String, Object>>>of(
                Map.entry("url", Map.of("url", "https://mcp.example.com?key=${env:TOKEN}")),
                Map.entry("endpoint", Map.of("url", "https://mcp.example.com", "endpoint", "/mcp?k=${env:TOKEN}")),
                Map.entry("command", Map.of("command", "run-${env:TOKEN}")),
                Map.entry("args", Map.of("command", "node", "args", List.of("--token=${env:TOKEN}")))
        );
        for (var entry : cases) {
            var ex = assertThrows(McpSecretExpander.UnsupportedSecretLocationException.class,
                    () -> McpSecretExpander.expand("demo", entry.getValue(), Map.of("TOKEN", SECRET)));
            assertEquals(entry.getKey(), ex.fieldName());
            assertFalse(ex.getMessage().contains("${env:TOKEN}"));
            assertFalse(ex.getMessage().contains(SECRET));
            assertFalse(ex.getMessage().contains("mcp.example.com"));
        }
    }

    @Test
    void missingSecretFailsClosedWithVariableNameOnly() {
        Map<String, Object> config = Map.of(
                "command", "node",
                "env", Map.of("MY_TOKEN", "${env:MISSING_VAR}")
        );
        var ex = assertThrows(McpSecretExpander.MissingSecretException.class,
                () -> McpSecretExpander.expand("demo", config, Map.of()));
        assertEquals("MISSING_VAR", ex.secretName());
        assertTrue(ex.getMessage().contains("MISSING_VAR"));
    }

    @Test
    void sanitizerRedactsSecretsUrlsAuthAndAnsiButSkipsShortValues() {
        McpLogSanitizer.registerSecrets(List.of(SECRET, "", "abc"));
        var raw = "\u001B[32mConnecting\u001B[0m https://admin:pw@mcp.example.com:8443/v1/mcp?token="
                + SECRET + " Authorization: Bearer " + SECRET + " abc ok";
        var clean = McpLogSanitizer.sanitizeText(raw);

        assertFalse(clean.contains(SECRET));
        assertFalse(clean.contains("admin:pw"));
        assertFalse(clean.contains("?token="));
        assertFalse(clean.contains("\u001B["));
        assertTrue(clean.contains("https://mcp.example.com:8443/v1/mcp"));
        assertTrue(clean.contains("Bearer ***"));
        assertTrue(clean.contains("abc ok"));
    }

    @Test
    void classifyErrorReturnsFixedCodeAndNeverEchoesRawMessage() {
        var timeout = McpLogSanitizer.classifyError(new TimeoutException("secret " + SECRET));
        assertEquals("MCP_PROBE_TIMEOUT", timeout.code());
        assertFalse(timeout.message().contains(SECRET));

        var conn = McpLogSanitizer.classifyError(
                new ConnectException("Connection refused to https://user:pass@127.0.0.1:9999/mcp?k=" + SECRET));
        assertEquals("MCP_CONNECTION_FAILED", conn.code());
        assertFalse(conn.message().contains(SECRET));
        assertFalse(conn.message().contains("user:pass"));

        var auth = McpLogSanitizer.classifyError(
                new RuntimeException("HTTP 401 Unauthorized Bearer " + SECRET));
        assertEquals("MCP_AUTH_FAILED", auth.code());
        assertFalse(auth.message().contains(SECRET));
    }
}
