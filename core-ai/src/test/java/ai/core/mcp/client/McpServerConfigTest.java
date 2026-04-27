package ai.core.mcp.client;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
