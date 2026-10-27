package ai.core.server.settings;

import core.framework.web.exception.BadRequestException;

import java.util.function.LongSupplier;

/**
 * How large a chat attachment may be and still be copied into a session sandbox. Larger files stay in
 * platform storage and reach the agent as an attachment reference (and, for videos, a download hint).
 *
 * @author stephen
 */
public final class SandboxAttachmentLimit {
    public static final long DEFAULT_BYTES = 32L * 1024 * 1024;
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final long MIN_BYTES = 1024 * 1024;

    public static void validate(Long value) {
        if (value == null) return;
        if (value < MIN_BYTES || value > MAX_BYTES) {
            throw new BadRequestException("sandbox attachment max bytes must be between " + MIN_BYTES + " and " + MAX_BYTES);
        }
    }

    /**
     * The configured limit, re-read from the settings document on every staging decision so a change in
     * System Configuration applies without a restart. Falls back to the deployment default when unset.
     */
    public static LongSupplier liveLimit(SystemSettingsService settings, long fallback) {
        return () -> {
            var entity = settings.entity();
            var configured = entity == null ? null : entity.sandboxAttachmentMaxBytes;
            return configured == null || configured <= 0 ? fallback : configured;
        };
    }

    private SandboxAttachmentLimit() {
    }
}

