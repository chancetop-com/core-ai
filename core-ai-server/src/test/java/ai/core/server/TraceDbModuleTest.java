package ai.core.server;

import ai.core.server.trace.PgTraceRow;
import core.framework.async.Task;
import core.framework.db.Database;
import core.framework.internal.log.LogManager;
import core.framework.internal.module.ModuleContext;
import core.framework.module.DBConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @author stephen
 */
class TraceDbModuleTest {
    @Test
    void inheritsMainDatabaseWhenTraceUrlIsBlank() {
        var module = new RecordingTraceDbModule(Map.of(
            "sys.store.mode", "pg",
            "sys.db.url", "jdbc:postgresql://main:5432/core_ai",
            "sys.db.user", "main_user",
            "sys.db.password", "main_password"
        ));

        module.initializeModule();

        verify(module.config).url("jdbc:postgresql://main:5432/core_ai");
        verify(module.config).user("main_user");
        verify(module.config).password("main_password");
        verify(module.config).poolSize(1, 4);
        verify(module.config).timeout(Duration.ofSeconds(10));
        assertTrue(module.views.contains(PgTraceRow.class));
        assertTrue(module.views.contains(PgSettingRow.class));
        assertEquals(1, module.startupTasks.size());
    }

    @Test
    void usesDedicatedTraceDatabaseWhenUrlIsSet() {
        var module = new RecordingTraceDbModule(Map.of(
            "sys.store.mode", "pg",
            "sys.db.url", "jdbc:postgresql://main:5432/core_ai",
            "sys.db.user", "main_user",
            "sys.db.password", "main_password",
            "sys.trace.db.url", "jdbc:postgresql://trace:5432/core_ai",
            "sys.trace.db.user", "trace_user",
            "sys.trace.db.password", "trace_password",
            "sys.trace.db.pool.min", "2",
            "sys.trace.db.pool.max", "8",
            "sys.trace.db.timeout.seconds", "20"
        ));

        module.initializeModule();

        verify(module.config).url("jdbc:postgresql://trace:5432/core_ai");
        verify(module.config).user("trace_user");
        verify(module.config).password("trace_password");
        verify(module.config).poolSize(2, 8);
        verify(module.config).timeout(Duration.ofSeconds(20));
    }

    @Test
    void sharesMainPoolWhenTraceDatasourceIsDisabled() {
        var module = new RecordingTraceDbModule(Map.of(
            "sys.store.mode", "pg",
            "sys.trace.db.enabled", "false"
        ));

        module.initializeModule();

        assertTrue(module.mainConfigViews.contains(PgTraceRow.class));
        assertTrue(module.views.isEmpty());
        assertTrue(module.startupTasks.isEmpty());
    }

    @Test
    void skipsNamedDatasourceInMongoMode() {
        var module = new RecordingTraceDbModule(Map.of("sys.store.mode", "mongo"));

        module.initializeModule();

        assertTrue(module.views.isEmpty());
        assertTrue(module.startupTasks.isEmpty());
        verify(module.config, never()).url(any());
    }

    private static final class RecordingTraceDbModule extends TraceDbModule {
        private final DBConfig config = mock(DBConfig.class);
        private final DBConfig mainConfig = mock(DBConfig.class);
        private final Database mainDatabase = mock(Database.class);
        private final List<Class<?>> views = new ArrayList<>();
        private final List<Class<?>> mainConfigViews = new ArrayList<>();
        private final List<Task> startupTasks = new ArrayList<>();

        private RecordingTraceDbModule(Map<String, String> properties) {
            context = new ModuleContext(new LogManager());
            properties.forEach(context.propertyManager.properties::set);
            doAnswer(invocation -> {
                views.add(invocation.getArgument(0));
                return null;
            }).when(config).view(any());
            doAnswer(invocation -> {
                mainConfigViews.add(invocation.getArgument(0));
                return null;
            }).when(mainConfig).view(any());
        }

        private void initializeModule() {
            initialize();
        }

        @Override
        public DBConfig db() {
            return mainConfig;
        }

        @Override
        public DBConfig db(String name) {
            if (!TraceDbModule.NAME.equals(name)) throw new IllegalArgumentException("unexpected datasource, name=" + name);
            return config;
        }

        @Override
        public <T> T bean(Class<T> instanceClass) {
            if (instanceClass == Database.class) return instanceClass.cast(mainDatabase);
            throw new IllegalArgumentException("unexpected bean, type=" + instanceClass.getName());
        }

        @Override
        public <T> T bind(Type type, String name, T instance) {
            return instance;
        }

        @Override
        public void onStartup(Task task) {
            startupTasks.add(task);
        }
    }
}
