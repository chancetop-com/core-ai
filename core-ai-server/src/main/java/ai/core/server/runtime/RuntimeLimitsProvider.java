package ai.core.server.runtime;

import ai.core.server.domain.SystemSettings;
import ai.core.server.settings.SystemSettingsProvider;
import ai.core.server.settings.SystemSettingsService;

/**
 * Hot-reloadable view of the runtime governance limits: every read picks up an admin change
 * without a restart, and the underlying instance is rebuilt only when a limit actually changed.
 *
 * @author stephen
 */
public class RuntimeLimitsProvider extends SystemSettingsProvider<RuntimeLimits> {
    public RuntimeLimitsProvider(SystemSettingsService settings) {
        super(settings);
    }

    @Override
    protected String fingerprint(SystemSettings entity) {
        return RuntimeLimits.fingerprint(entity);
    }

    @Override
    protected RuntimeLimits build(SystemSettings entity) {
        return RuntimeLimits.resolve(entity);
    }
}
