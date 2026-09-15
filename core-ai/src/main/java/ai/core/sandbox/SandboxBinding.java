package ai.core.sandbox;

/**
 * Everything a sandbox runtime needs to serve the sandbox hub on behalf of one session: where the
 * server is, the session token to replay (the sandbox never holds the user's credentials) and the
 * two names it exposes to scripts as informational environment variables.
 * <p>
 * The runtime keeps this in process memory only; it is never written into the pod spec, the
 * sandbox environment or any script's own environment.
 *
 * @author xander
 */
public record SandboxBinding(String serverUrl, String token, String sessionId, String agentName, long expiresAt) {
}
