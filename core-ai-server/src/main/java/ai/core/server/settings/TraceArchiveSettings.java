package ai.core.server.settings;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.server.domain.SystemSettings;
import ai.core.server.trace.maintenance.TraceArchivingJob;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;

/**
 * Trace archive interval bounds and persistence, owned here so the settings page and the
 * archive job resolve the interval the same way. The job reads it through {@link #intervalMinutes()}
 * on every run, so an admin change applies without a restart.
 *
 * @author stephen
 */
public class TraceArchiveSettings {
    private static final String SETTINGS_ID = "default";
    static final int MIN_INTERVAL_MINUTES = TraceArchivingJob.TICK_MINUTES;
    static final int MAX_INTERVAL_MINUTES = 7 * 24 * 60;

    static int resolveIntervalMinutes(SystemSettings entity) {
        var configured = entity == null ? null : entity.traceArchiveIntervalMinutes;
        return configured == null ? TraceArchivingJob.DEFAULT_INTERVAL_MINUTES : configured;
    }

    static void apply(SystemSettings entity, SystemSettingsRequest request) {
        if (request.traceArchiveIntervalMinutes != null) {
            entity.traceArchiveIntervalMinutes = request.traceArchiveIntervalMinutes;
        }
    }

    static void validate(SystemSettingsRequest request) {
        var interval = request.traceArchiveIntervalMinutes;
        if (interval == null) return;
        if (interval < MIN_INTERVAL_MINUTES || interval > MAX_INTERVAL_MINUTES) {
            throw new BadRequestException("trace archive interval must be between " + MIN_INTERVAL_MINUTES
                    + " and " + MAX_INTERVAL_MINUTES + " minutes");
        }
    }

    @Inject
    MongoCollection<SystemSettings> systemSettingsCollection;

    /** Interval between archive runs, defaulted when never configured. */
    public int intervalMinutes() {
        return resolveIntervalMinutes(systemSettingsCollection.get(SETTINGS_ID).orElse(null));
    }
}
