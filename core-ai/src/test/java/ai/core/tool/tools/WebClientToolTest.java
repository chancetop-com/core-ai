package ai.core.tool.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for WebClientTool
 * This test verifies parameter validation and error handling.
 * Note: Actual HTTP requests are not tested as they require a real HTTPClient.
 *
 * @author stephen
 */
class WebClientToolTest {
    private final Logger logger = LoggerFactory.getLogger(WebClientToolTest.class);
    private WebClientTool webClientTool;

    @BeforeEach
    void setUp() {
        webClientTool = new WebClientTool();
        // Note: client is null, so actual HTTP requests will fail
        // But we can still test parameter validation
    }

    @Test
    void testInvalidHttpMethod() {
        String result = webClientTool.call("https://api.example.com/data", "INVALID", null, null);

        logger.info("Invalid method result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("Invalid HTTP method"),
            "Result should indicate invalid HTTP method");
    }

    @Test
    void testMissingUrl() {
        String result = webClientTool.call("", "GET", null, null);

        logger.info("Missing URL result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("URL"),
            "Result should indicate URL is required");
    }

    @Test
    void testNullUrl() {
        String result = webClientTool.call(null, "GET", null, null);

        logger.info("Null URL result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("URL"),
            "Result should indicate URL is required");
    }

    @Test
    void testMissingMethod() {
        String result = webClientTool.call("https://api.example.com/data", "", null, null);

        logger.info("Missing method result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("method"),
            "Result should indicate method is required");
    }

    @Test
    void testNullMethod() {
        String result = webClientTool.call("https://api.example.com/data", null, null, null);

        logger.info("Null method result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("method"),
            "Result should indicate method is required");
    }

    @Test
    void testInvalidMethodVariations() {
        String[] invalidMethods = {"INVALID", "abc", "123", "get post", "G E T"};

        for (String method : invalidMethods) {
            String result = webClientTool.call("https://api.example.com/data", method, null, null);
            logger.info("Invalid method '{}' result: {}", method, result);

            assertNotNull(result, "Result should not be null for method: " + method);
            assertTrue(result.contains("Error") && result.contains("Invalid HTTP method"),
                "Result should indicate invalid HTTP method for: " + method);
        }
    }

    @Test
    void testSupportedHttpMethods() {
        String[] validMethods = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"};

        for (String method : validMethods) {
            String result = webClientTool.call("https://api.example.com/data", method, null, null);
            logger.info("Valid method '{}' result: {}", method, result);

            assertNotNull(result, "Result should not be null for method: " + method);
            // Should not contain "Invalid HTTP method" error
            assertTrue(!result.contains("Invalid HTTP method"),
                "Valid method should not produce 'Invalid HTTP method' error: " + method);
        }
    }

    @Test
    void testCaseInsensitiveHttpMethods() {
        String[][] methodVariations = {
            {"get", "Get", "GET", "GeT"},
            {"post", "Post", "POST", "PoSt"},
            {"put", "Put", "PUT"},
            {"delete", "Delete", "DELETE"}
        };

        for (String[] variations : methodVariations) {
            for (String method : variations) {
                String result = webClientTool.call("https://api.example.com/data", method, null, null);
                logger.info("Case variation '{}' result: {}", method, result);

                assertNotNull(result, "Result should not be null for method: " + method);
                assertTrue(!result.contains("Invalid HTTP method"),
                    "Case variation should be accepted: " + method);
            }
        }
    }

    @Test
    void testParameterValidationOrder() {
        // Test that URL validation happens before method validation
        String result1 = webClientTool.call("", "INVALID", null, null);
        assertTrue(result1.contains("URL"),
            "URL validation should happen first");

        // Test that method validation happens after URL validation
        String result2 = webClientTool.call("https://api.example.com", "INVALID", null, null);
        assertTrue(result2.contains("Invalid HTTP method"),
            "Method validation should happen after URL validation");
    }

    @Test
    void testEmptyBodyHandling() {
        // Empty body should not cause errors
        String result = webClientTool.call("https://api.example.com/data", "POST", "application/json", "");

        logger.info("Empty body result: {}", result);
        assertNotNull(result, "Result should not be null");
        // Should not contain parameter validation errors
        assertTrue(!result.contains("URL") && !result.contains("Invalid HTTP method"),
            "Empty body should not cause parameter validation errors");
    }
}

