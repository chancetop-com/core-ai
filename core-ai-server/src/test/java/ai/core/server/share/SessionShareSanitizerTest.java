package ai.core.server.share;

import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Xander
 */
class SessionShareSanitizerTest {
    @Test
    void redactsNestedJsonWithoutRemovingUsefulExecutionData() throws Exception {
        var value = JsonUtil.toJson(Map.of("headers", Map.of("Authorization", "Bearer very-private", "Accept", "application/json"),
            "arguments", "{\"api_key\":\"nested-secret\",\"query\":\"restaurant\"}", "input_tokens", 42));
        var sanitized = SessionShareSanitizer.sanitize(value);
        assertFalse(sanitized.contains("very-private"));
        assertFalse(sanitized.contains("nested-secret"));
        var node = JsonUtil.OBJECT_MAPPER.readTree(sanitized);
        assertEquals(42, node.get("input_tokens").intValue());
        assertEquals("application/json", node.get("headers").get("Accept").textValue());
        assertTrue(node.get("arguments").textValue().contains("restaurant"));
    }

    @Test
    void redactsCredentialsInUrlsAndHeaderText() {
        var value = "https://user:pass@example.test/file?sig=super-secret&name=report\n"
            + "Authorization: Bearer bearer-credential\nCookie: session=private-cookie\nAnswer: 42";
        var sanitized = SessionShareSanitizer.sanitize(value);
        for (var secret : List.of("pass", "super-secret", "bearer-credential", "private-cookie")) {
            assertFalse(sanitized.contains(secret), secret);
        }
        assertTrue(sanitized.contains("Answer: 42"));
    }

    @Test
    void preservesPromptsErrorsAndTokenCounts() {
        var value = "Use the supplied memory. Tool failed: file not found. 128 tokens.";
        assertEquals(value, SessionShareSanitizer.sanitize(value));
        assertNull(SessionShareSanitizer.sanitize(null));
    }

    @Test
    void doesNotDiscardTextFollowingJson() {
        var text = "{\"result\":42}\nThe tool failed after this result.";
        assertEquals(text, SessionShareSanitizer.sanitize(text));
    }

    @Test
    void redactsEveryCookieInAHeader() {
        var text = "Cookie: sid=\"first-secret\"; auth=second-secret\nSet-Cookie: token=third-secret; Path=/\nDone";
        var sanitized = SessionShareSanitizer.sanitize(text);
        assertFalse(sanitized.contains("first-secret"));
        assertFalse(sanitized.contains("second-secret"));
        assertFalse(sanitized.contains("third-secret"));
        assertTrue(sanitized.endsWith("Done"));
    }

    @Test
    void redactsNamedHeadersAndHeaderPairs() {
        var text = "{\"headers\":[{\"name\":\"Authorization\",\"value\":\"header-secret\"},"
            + "[\"X-Api-Key\",\"tuple-secret\"],{\"name\":\"Accept\",\"value\":\"application/json\"}]}";
        var sanitized = SessionShareSanitizer.sanitize(text);
        assertFalse(sanitized.contains("header-secret"));
        assertFalse(sanitized.contains("tuple-secret"));
        assertTrue(sanitized.contains("application/json"));
    }
}
