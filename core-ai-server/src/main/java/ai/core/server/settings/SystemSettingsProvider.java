package ai.core.server.settings;

import ai.core.server.domain.SystemSettings;

import java.util.Objects;

/**
 * Unified pattern for service-instance config domains backed by Mongo system settings.
 * <p>
 * Reads the current settings document on every call and rebuilds the underlying instance
 * only when the domain fingerprint changes. Configuration changes therefore take effect
 * immediately on all replicas without restart, listener, schedule or event broadcast —
 * each pod re-reads Mongo and refreshes its own process-local cache.
 *
 * @author stephen
 */
public abstract class SystemSettingsProvider<T> {
    protected final SystemSettingsService settings;
    private volatile String cachedFingerprint;
    private volatile T cached;

    protected SystemSettingsProvider(SystemSettingsService settings) {
        this.settings = settings;
    }

    public final T get() {
        var entity = settings.entity();
        var fingerprint = fingerprint(entity);
        if (!Objects.equals(fingerprint, cachedFingerprint)) {
            cached = build(entity);
            cachedFingerprint = fingerprint;
        }
        return cached;
    }

    /** Domain-specific fingerprint derived from the settings document; null means "not configured". */
    protected abstract String fingerprint(SystemSettings entity);

    /** Builds the service instance from the settings document; return null when not fully configured. */
    protected abstract T build(SystemSettings entity);
}
