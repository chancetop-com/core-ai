package ai.core.cli.auth;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory active authentication config, populated by the CLI on login
 * or server-switch.  Consumed as a fallback when LLM provider credentials
 * (e.g. {@code litellm.api.base / litellm.api.key}) are not explicitly
 * set in {@code agent.properties}.
 * <p>
 * Listeners registered via {@link #addListener(Runnable)} are fired on
 * every {@link #update} — this allows long-lived objects (e.g. providers)
 * to react to credential changes without restart.
 * <p>
 * Thread-safe via volatile fields + CopyOnWriteArrayList — writes from
 * auth/login thread, reads from bootstrap / listener threads.
 *
 * @author cyril
 */
public final class RuntimeAuthConfig {
    private static final RuntimeAuthConfig INSTANCE = new RuntimeAuthConfig();

    public static RuntimeAuthConfig instance() {
        return INSTANCE;
    }

    private volatile String serverUrl;
    private volatile String apiKey;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private RuntimeAuthConfig() {
    }

    /**
     * Register a listener that runs after every {@link #update}.
     * Useful for re-initializing components that depend on auth credentials.
     */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Update from the active login session (e.g. on login / server switch). */
    public void update(String serverUrl, String apiKey) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        listeners.forEach(Runnable::run);
    }

    /** Clear the active session (e.g. on logout). */
    public void clear() {
        this.serverUrl = null;
        this.apiKey = null;
        listeners.forEach(Runnable::run);
    }

    public String serverUrl() {
        return serverUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public boolean isConfigured() {
        return serverUrl != null && !serverUrl.isBlank();
    }
}
