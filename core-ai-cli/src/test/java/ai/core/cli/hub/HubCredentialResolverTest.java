package ai.core.cli.hub;

import ai.core.cli.auth.AuthConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubCredentialResolverTest {
    private final HubGlobalOptions options = new HubGlobalOptions();

    @Test
    void flagsBeatEnvironmentVariables() {
        options.server = "https://flag";
        options.apiKey = "flag-key";
        var env = Map.of("CORE_AI_SERVER", "https://env", "CORE_AI_API_KEY", "env-key");

        var credentials = resolver().resolve(options, env, lookup());

        assertEquals("https://flag", credentials.serverUrl());
        assertEquals("flag-key", credentials.apiKey());
    }

    @Test
    void environmentVariablesBeatAuthFile() {
        var env = Map.of("CORE_AI_SERVER", "https://env", "CORE_AI_API_KEY", "env-key");
        var auth = lookup(active("https://auth", "auth-key"));

        var credentials = resolver().resolve(options, env, auth);

        assertEquals("https://env", credentials.serverUrl());
        assertEquals("env-key", credentials.apiKey());
    }

    @Test
    void activeAuthRecordIsUsedWhenNothingElseGiven() {
        var credentials = resolver().resolve(options, Map.of(), lookup(active("https://auth", "auth-key")));
        assertEquals("https://auth", credentials.serverUrl());
        assertEquals("auth-key", credentials.apiKey());
    }

    @Test
    void serverOnlyFlagCompletesKeyFromMatchingAuthRecord() {
        options.server = "https://server-a";
        var auth = lookup(new AuthConfig("https://server-a", "a-key", null, null, null, null, true, false));

        var credentials = resolver().resolve(options, Map.of(), auth);

        assertEquals("https://server-a", credentials.serverUrl());
        assertEquals("a-key", credentials.apiKey());
    }

    @Test
    void insecureFlagIsCarriedOnTheCredentials() {
        options.server = "https://flag";
        options.apiKey = "flag-key";
        options.insecure = true;

        var credentials = resolver().resolve(options, Map.of(), lookup());

        assertTrue(credentials.insecure());
    }

    @Test
    void storedInsecureLoginCarriesOverToHubCommands() {
        var credentials = resolver().resolve(options, Map.of(), lookup(active("https://localhost:8443", "k", true)));

        assertEquals("https://localhost:8443", credentials.serverUrl());
        assertTrue(credentials.insecure());
    }

    @Test
    void storedInsecureLoginAlsoCarriesOverWithAnExplicitServerFlag() {
        options.server = "https://localhost:8443";
        var auth = lookup(new AuthConfig("https://localhost:8443", "a-key", null, null, null, null, true, true));

        var credentials = resolver().resolve(options, Map.of(), auth);

        assertTrue(credentials.insecure());
    }

    @Test
    void insecureLoginOfAnotherServerDoesNotLeakIntoAFlaggedServer() {
        options.server = "https://server-a";
        options.apiKey = "a-key";
        var auth = lookup(active("https://localhost:8443", "local-key", true));

        var credentials = resolver().resolve(options, Map.of(), auth);

        assertEquals("https://server-a", credentials.serverUrl());
        assertFalse(credentials.insecure());
    }

    @Test
    void verifiedLoginStaysVerified() {
        var credentials = resolver().resolve(options, Map.of(), lookup(active("https://auth", "auth-key", false)));
        assertFalse(credentials.insecure());
    }

    @Test
    void missingServerIsUsageError() {
        var env = Map.of("CORE_AI_API_KEY", "k");
        var error = assertThrows(HubCliError.class, () -> resolver().resolve(options, env, lookup()));
        assertEquals(HubExitCodes.USAGE, error.exitCode);
    }

    @Test
    void missingKeyIsUnauthenticatedError() {
        options.server = "https://server-a";
        var error = assertThrows(HubCliError.class, () -> resolver().resolve(options, Map.of(), lookup()));
        assertEquals(HubExitCodes.UNAUTHENTICATED, error.exitCode);
    }

    @Test
    void serverTrailingSlashIsNormalized() {
        options.server = "https://server-a/";
        options.apiKey = "k";
        var credentials = resolver().resolve(options, Map.of(), lookup());
        assertEquals("https://server-a", credentials.serverUrl());
    }

    private HubCredentialResolver resolver() {
        return new HubCredentialResolver();
    }

    private HubCredentialResolver.AuthLookup lookup() {
        return lookup(null);
    }

    private HubCredentialResolver.AuthLookup lookup(AuthConfig auth) {
        return new HubCredentialResolver.AuthLookup() {
            @Override
            public AuthConfig active() {
                return auth;
            }

            @Override
            public AuthConfig byServerUrl(String serverUrl) {
                return auth != null && serverUrl.equals(auth.serverUrl()) ? auth : null;
            }
        };
    }

    private AuthConfig active(String serverUrl, String apiKey) {
        return active(serverUrl, apiKey, false);
    }

    private AuthConfig active(String serverUrl, String apiKey, boolean insecure) {
        return new AuthConfig(serverUrl, apiKey, null, null, null, null, true, insecure);
    }
}
