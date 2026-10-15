package ai.core.cli.auth;

import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TLS opt-in of a login is part of the stored credential: the desktop writes the same
 * {@code auth.json} the CLI reads, so the property name and the default for older records
 * are pinned here.
 *
 * @author stephen
 */
class AuthConfigTest {
    @Test
    void insecureOptInRoundTripsThroughAuthJson() {
        var json = JsonUtil.toJson(AuthConfig.login("https://localhost:8443", "key-1", true));

        assertTrue(json.contains("\"insecure\":true"), json);
        var parsed = JsonUtil.fromJson(AuthConfig.class, json);
        assertTrue(parsed.insecure());
        assertEquals("https://localhost:8443", parsed.serverUrl());
        assertEquals("key-1", parsed.apiKey());
    }

    @Test
    void recordsWithoutTheFlagKeepTlsVerification() {
        var parsed = JsonUtil.fromJson(AuthConfig.class,
                "{\"server_url\":\"https://uat\",\"api_key\":\"k\",\"active\":true}");

        assertEquals("https://uat", parsed.serverUrl());
        assertFalse(parsed.insecure());
    }

    @Test
    void fullConfigKeepsTheOptIn() {
        assertTrue(AuthConfig.full("https://localhost:8443", "k", "u", "n", "admin", true).insecure());
        assertFalse(AuthConfig.full("https://uat", "k", "u", "n", "admin").insecure());
        assertFalse(AuthConfig.login("https://uat", "k").insecure());
    }
}
