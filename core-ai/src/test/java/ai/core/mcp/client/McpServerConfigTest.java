package ai.core.mcp.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * @author stephen
 */
class McpServerConfigTest {
    private static final String CLEAN_URL = "https://mcp.connexup-uat.net/superset/mcp";

    @Test
    void httpUrlStripsBom() {
        var config = McpServerConfig.http("﻿" + CLEAN_URL).build();
        assertEquals(CLEAN_URL, config.getUrl());
        assertDoesNotThrow(() -> URI.create(config.getUrl()));
    }

    @Test
    void httpUrlStripsZeroWidthAndWhitespace() {
        var config = McpServerConfig.http("  ​" + CLEAN_URL + "\t ").build();
        assertEquals(CLEAN_URL, config.getUrl());
    }

    @Test
    void httpUrlStripsSurroundingQuotes() {
        var config = McpServerConfig.http("\"" + CLEAN_URL + "\"").build();
        assertEquals(CLEAN_URL, config.getUrl());
    }

    @Test
    void cleanUrlUntouched() {
        var config = McpServerConfig.http(CLEAN_URL).build();
        assertEquals(CLEAN_URL, config.getUrl());
    }

    @Test
    void metaAdsConfigPrefersJsonResponses() {
        var config = McpServerConfig.fromMap("meta-ads", Map.of(
            "url", "https://mcp.facebook.com",
            "endpoint", "/ads",
            "headers", Map.of("Authorization", "Bearer test-token")
        ));

        assertEquals("application/json", config.getHeaders().get("Accept"));
        assertEquals("Bearer test-token", config.getHeaders().get("Authorization"));
    }

    @Test
    void directMetaAdsBuilderPrefersJsonResponses() {
        var config = McpServerConfig.http("https://mcp.facebook.com/ads").build();

        assertEquals("application/json", config.getHeaders().get("Accept"));
    }

    @Test
    void metaAdsEndpointWithQueryPrefersJsonResponses() {
        var config = McpServerConfig.http("https://mcp.facebook.com")
            .endpoint("/ads?version=1")
            .build();

        assertEquals("application/json", config.getHeaders().get("Accept"));
    }

    @Test
    void metaAdsSseConfigDoesNotOverrideAccept() {
        var config = McpServerConfig.http("https://mcp.facebook.com/ads")
            .transportType(TransportType.SSE)
            .build();

        assertFalse(config.getHeaders().containsKey("Accept"));
    }

    @Test
    void metaAdsConfigPreservesExplicitAcceptCaseInsensitively() {
        var config = McpServerConfig.http("https://mcp.facebook.com/ads")
            .header("accept", "application/json, text/event-stream")
            .build();

        assertEquals("application/json, text/event-stream", config.getHeaders().get("accept"));
        assertFalse(config.getHeaders().containsKey("Accept"));
    }

    @Test
    void nonMetaConfigDoesNotOverrideAccept() {
        var config = McpServerConfig.http(CLEAN_URL).build();

        assertFalse(config.getHeaders().containsKey("Accept"));
    }
}
