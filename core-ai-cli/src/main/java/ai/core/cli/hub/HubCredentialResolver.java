package ai.core.cli.hub;

import ai.core.cli.auth.AuthConfig;

import java.util.Map;

/**
 * Resolves server URL + API key for hub commands.
 * <p>
 * Priority: {@code --server/--api-key} flags → {@code CORE_AI_SERVER/CORE_AI_API_KEY}
 * environment variables → {@code ~/.core-ai/auth.json}. Each side may be completed
 * from a lower source: when only {@code --server} is given the key is looked up in the
 * auth record for that exact URL, otherwise the active auth record is used.
 *
 * @author stephen
 */
public class HubCredentialResolver {
    private static final String ENV_SERVER = "CORE_AI_SERVER";
    private static final String ENV_API_KEY = "CORE_AI_API_KEY";

    public static AuthLookup authLookup() {
        return new AuthLookup() {
            @Override
            public AuthConfig active() {
                return AuthConfig.load();
            }

            @Override
            public AuthConfig byServerUrl(String serverUrl) {
                return AuthConfig.load(serverUrl);
            }
        };
    }

    public HubCredentials resolve(HubGlobalOptions options, Map<String, String> env, AuthLookup authLookup) {
        String serverUrl = normalizeServer(firstNonNull(options.server, env.get(ENV_SERVER)));
        String apiKey = options.apiKey != null ? options.apiKey : env.get(ENV_API_KEY);
        if (apiKey == null) {
            var resolved = lookupKeyAndServer(authLookup, serverUrl);
            if (resolved != null) {
                serverUrl = resolved.serverUrl();
                apiKey = resolved.apiKey();
            }
        }
        if (serverUrl == null) {
            throw new HubCliError(HubExitCodes.USAGE,
                    "server is required: pass --server URL, set CORE_AI_SERVER, or run 'core-ai-cli --login'");
        }
        if (apiKey == null) {
            throw new HubCliError(HubExitCodes.UNAUTHENTICATED,
                    "not authenticated for " + serverUrl + ": pass --api-key, set CORE_AI_API_KEY, "
                            + "or run 'core-ai-cli --login'");
        }
        return new HubCredentials(serverUrl, apiKey);
    }

    private Resolved lookupKeyAndServer(AuthLookup authLookup, String serverUrl) {
        if (serverUrl == null) return resolveActiveRecord(authLookup);
        var matching = authLookup.byServerUrl(serverUrl);
        return matching == null ? null : new Resolved(serverUrl, matching.apiKey());
    }

    private Resolved resolveActiveRecord(AuthLookup authLookup) {
        var active = authLookup.active();
        if (active == null) return null;
        if (active.serverUrl() == null) return null;
        return new Resolved(normalizeServer(active.serverUrl()), active.apiKey());
    }

    private String firstNonNull(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second != null && !second.isBlank() ? second : null;
    }

    private String normalizeServer(String serverUrl) {
        if (serverUrl == null) return null;
        return serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
    }

    public interface AuthLookup {
        AuthConfig active();

        AuthConfig byServerUrl(String serverUrl);
    }

    public record HubCredentials(String serverUrl, String apiKey) {
    }

    private record Resolved(String serverUrl, String apiKey) {
    }
}
