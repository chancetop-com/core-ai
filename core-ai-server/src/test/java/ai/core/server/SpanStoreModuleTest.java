package ai.core.server;

import ai.core.server.trace.MongoSpanStore;
import ai.core.server.trace.PgSpanStore;
import ai.core.server.trace.SpanMigrationService;
import ai.core.server.trace.SpanStore;
import core.framework.async.Task;
import core.framework.internal.log.LogManager;
import core.framework.internal.module.ModuleContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * @author xander
 */
class SpanStoreModuleTest {
    @Test
    void defaultsToMongoWithoutAnOverrideOrGlobalMode() {
        var module = new RecordingSpanStoreModule(Map.of());

        module.initialize();

        assertInstanceOf(MongoSpanStore.class, module.store);
        assertTrue(module.startupTasks.isEmpty());
    }

    @Test
    void inheritsGlobalPostgresWhenOverrideIsMissing() throws Exception {
        var module = new RecordingSpanStoreModule(Map.of("sys.store.mode", "pg"));

        module.initialize();

        assertInstanceOf(PgSpanStore.class, module.store);
        assertEquals(1, module.startupTasks.size());
        verifyNoInteractions(module.migration);
        module.startupTasks.getFirst().execute();
        verify(module.migration).start(true);
    }

    @Test
    void autoFollowsBothExistingGlobalModes() {
        for (var mode : List.of("mongo", "pg")) {
            var module = new RecordingSpanStoreModule(Map.of("sys.store.mode", mode, "sys.trace.span.store.mode", "auto"));

            module.initialize();

            Class<? extends SpanStore> expected = "pg".equals(mode) ? PgSpanStore.class : MongoSpanStore.class;
            assertInstanceOf(expected, module.store);
            assertEquals("pg".equals(mode) ? 1 : 0, module.startupTasks.size());
        }
    }

    @Test
    void mongoOverrideKeepsHistoryInMongoWithoutBindingOrStartingMigration() {
        var module = new RecordingSpanStoreModule(Map.of("sys.store.mode", "pg", "sys.trace.span.store.mode", "mongo",
            "sys.trace.span.migration", "copy"));

        module.initialize();

        assertInstanceOf(MongoSpanStore.class, module.store);
        assertFalse(module.boundClasses.contains(PgSpanStore.class));
        assertFalse(module.boundClasses.contains(SpanMigrationService.class));
        assertTrue(module.startupTasks.isEmpty());
        verifyNoInteractions(module.migration);
    }

    @Test
    void mongoOverridePassesFrameworkValidationWithDeclaredMigrationProperty() {
        for (var migration : List.of("copy", "skip")) {
            var module = new RecordingSpanStoreModule(Map.of("sys.store.mode", "pg", "sys.trace.span.store.mode", "mongo",
                "sys.trace.span.migration", migration));

            module.initialize();
            module.validateConfiguration();

            assertInstanceOf(MongoSpanStore.class, module.store);
            assertFalse(module.boundClasses.contains(SpanMigrationService.class));
            assertTrue(module.startupTasks.isEmpty());
            verifyNoInteractions(module.migration);
        }
    }

    @Test
    void explicitPostgresRetainsConfiguredMigrationSkip() throws Exception {
        var module = new RecordingSpanStoreModule(Map.of("sys.store.mode", "pg", "sys.trace.span.store.mode", "pg",
            "sys.trace.span.migration", "skip"));

        module.initialize();

        assertInstanceOf(PgSpanStore.class, module.store);
        assertEquals(1, module.startupTasks.size());
        module.startupTasks.getFirst().execute();
        verify(module.migration).start(false);
    }

    @Test
    void rejectsInvalidOverrideBeforeBindingAStore() {
        for (var mode : List.of("postgresql", "PG")) {
            var module = new RecordingSpanStoreModule(Map.of("sys.store.mode", "pg", "sys.trace.span.store.mode", mode));

            var error = assertThrows(IllegalArgumentException.class, module::initialize);

            assertTrue(error.getMessage().contains("sys.trace.span.store.mode"));
            assertTrue(module.boundClasses.isEmpty());
            assertTrue(module.startupTasks.isEmpty());
        }
    }

    @Test
    void rejectsPostgresOverrideWithoutGlobalPostgresDatabaseWiring() {
        var module = new RecordingSpanStoreModule(Map.of("sys.store.mode", "mongo", "sys.trace.span.store.mode", "pg"));

        var error = assertThrows(IllegalArgumentException.class, module::initialize);

        assertTrue(error.getMessage().contains("sys.store.mode=pg"));
        assertTrue(module.boundClasses.isEmpty());
        assertTrue(module.startupTasks.isEmpty());
    }

    @Test
    void declaresAutoDefaultSoEnvironmentOverrideIsRecognized() throws Exception {
        var properties = new Properties();
        var resources = Thread.currentThread().getContextClassLoader().getResources("sys.properties");
        while (resources.hasMoreElements()) {
            try (var input = resources.nextElement().openStream()) {
                properties.load(input);
            }
        }

        assertEquals("auto", properties.getProperty("sys.trace.span.store.mode"));
    }

    private static final class RecordingSpanStoreModule extends SpanStoreModule {
        private final List<Class<?>> boundClasses = new ArrayList<>();
        private final List<Task> startupTasks = new ArrayList<>();
        private final SpanMigrationService migration = mock(SpanMigrationService.class);
        private SpanStore store;

        RecordingSpanStoreModule(Map<String, String> properties) {
            context = new ModuleContext(new LogManager());
            properties.forEach(context.propertyManager.properties::set);
        }

        void validateConfiguration() {
            context.validate();
        }

        @Override
        public <T> T bind(Class<T> instanceClass) {
            boundClasses.add(instanceClass);
            if (instanceClass == SpanMigrationService.class) return instanceClass.cast(migration);
            return mock(instanceClass);
        }

        @Override
        public <T> T bind(Type type, String name, T instance) {
            if (type == SpanStore.class) store = (SpanStore) instance;
            return instance;
        }

        @Override
        public void onStartup(Task task) {
            startupTasks.add(task);
        }
    }
}
