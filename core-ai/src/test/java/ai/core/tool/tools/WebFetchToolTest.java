package ai.core.tool.tools;

import core.framework.json.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class WebFetchToolTest {
    private final Logger logger = LoggerFactory.getLogger(WebFetchToolTest.class);
    private WebFetchTool webFetchTool;

    @BeforeEach
    void setUp() {
        webFetchTool = WebFetchTool.builder().build();
    }

    @Test
    void testFetchNocca() {
        Map<String, Object> args = new HashMap<>();
        args.put("url", "https://www.nocca.co/");
        args.put("method", "GET");

        var result = webFetchTool.execute(JSON.toJSON(args));
        logger.info("Fetch nocca.co status: {}", result.getStatus());
        logger.info("Fetch nocca.co result length: {}", result.getResult().length());
        logger.info("Fetch nocca.co result preview: {}", result.getResult().substring(0, Math.min(500, result.getResult().length())));

        assertNotNull(result.getResult(), "Result should not be null");
        assertFalse(result.getResult().startsWith("HTTP request failed with status 403"), "Should not get 403 error");
        assertTrue(result.getResult().contains("<!DOCTYPE html>"), "Should contain HTML content");
        assertTrue(result.getResult().length() > 10000, "Should have substantial content");
    }

    @Test
    void testFetchHttpBin() {
        Map<String, Object> args = new HashMap<>();
        args.put("url", "https://httpbin.org/get");
        args.put("method", "GET");

        var result = webFetchTool.execute(JSON.toJSON(args));
        logger.info("Fetch httpbin result: {}", result.getResult());

        assertNotNull(result.getResult(), "Result should not be null");
        assertTrue(result.getResult().contains("headers"), "Should contain headers info");
    }
}
