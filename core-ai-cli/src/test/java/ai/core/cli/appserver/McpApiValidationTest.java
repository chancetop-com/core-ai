package ai.core.cli.appserver;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class McpApiValidationTest {
    private static final String SECRET = "SUPER_SECRET_VALUE_123";

    @Test
    void rejectsUnsupportedPlaceholderLocationWithFixedCodeAndNoEcho() {
        var urlEx = assertThrows(RpcException.class, () -> McpApiValidation.validateDefinition(
                "bad-url", Map.of("url", "https://user:pass@example.com/mcp?t=${env:TOKEN}")));
        assertEquals("MCP_SECRET_UNSUPPORTED_LOCATION", urlEx.data().path("code").asText());
        assertEquals("url", urlEx.data().path("field").asText());
        assertFalse(urlEx.getMessage().contains("example.com"));
        assertFalse(urlEx.getMessage().contains("user:pass"));

        var argsEx = assertThrows(RpcException.class, () -> McpApiValidation.validateDefinition(
                "bad-args", Map.of("command", "node", "args", List.of("--key=${env:TOKEN}"))));
        assertEquals("MCP_SECRET_UNSUPPORTED_LOCATION", argsEx.data().path("code").asText());
        assertEquals("args", argsEx.data().path("field").asText());
        assertFalse(argsEx.getMessage().contains("--key="));
    }

    @Test
    void rejectsInvalidConfigWithoutEchoingUnderlyingExceptionMessage() {
        var ex = assertThrows(RpcException.class, () -> McpApiValidation.validateDefinition(
                "bad", Map.of("unknown", SECRET)));
        assertEquals("MCP_CONFIG_INVALID", ex.data().path("code").asText());
        assertFalse(ex.getMessage().contains(SECRET));

        var rejection = McpApiValidation.rejectionFor(new IllegalStateException("leaked " + SECRET));
        assertTrue(rejection.startsWith("MCP_CONFIG_INVALID:"));
        assertFalse(rejection.contains(SECRET));
    }
}
