package ai.core.server.settings;

import ai.core.server.domain.SystemSettings;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SystemSettingsProviderTest {

    private static SystemSettingsService fakeSettings(SystemSettings entity) {
        return new SystemSettingsService() {
            @Override
            SystemSettings entity() {
                return entity;
            }
        };
    }

    @Test
    void cachesInstanceUntilFingerprintChanges() {
        var entity = new SystemSettings();
        var builds = new AtomicInteger();
        var provider = new SystemSettingsProvider<>(fakeSettings(entity)) {
            @Override
            protected String fingerprint(SystemSettings e) {
                return e == null ? "" : e.githubAppId;
            }

            @Override
            protected Object build(SystemSettings e) {
                builds.incrementAndGet();
                return e == null ? null : new Object();
            }
        };

        entity.githubAppId = "app-1";
        provider.get();
        provider.get();
        assertEquals(1, builds.get());

        entity.githubAppId = "app-2";
        provider.get();
        assertEquals(2, builds.get());
        provider.get();
        assertEquals(2, builds.get());
    }

    @Test
    void rebuildsWhenConfigurationCleared() {
        var entity = new SystemSettings();
        var provider = new SystemSettingsProvider<>(fakeSettings(entity)) {
            @Override
            protected String fingerprint(SystemSettings e) {
                return e == null || e.githubAppId == null ? "" : e.githubAppId;
            }

            @Override
            protected Object build(SystemSettings e) {
                return e != null && e.githubAppId != null ? new Object() : null;
            }
        };

        entity.githubAppId = "app-1";
        var configured = provider.get();
        assertEquals(configured, provider.get());

        entity.githubAppId = null;
        assertNull(provider.get());
        assertNull(provider.get());
    }
}
