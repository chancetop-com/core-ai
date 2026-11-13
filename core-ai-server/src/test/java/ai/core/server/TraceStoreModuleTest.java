package ai.core.server;

import ai.core.server.apiuser.ApiUserUsageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.run.AgentRunStore;
import ai.core.server.trace.MongoTraceStore;
import ai.core.server.trace.SpanStore;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceDailyStats;
import ai.core.server.trace.maintenance.StaleRunCleanupService;
import ai.core.server.trace.maintenance.TraceArchiveService;
import ai.core.server.trace.service.TracePayloadStore;
import core.framework.async.Task;
import core.framework.internal.log.LogManager;
import core.framework.internal.module.ModuleContext;
import core.framework.internal.module.Config;
import core.framework.module.Module;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.module.MongoConfig;
import core.framework.util.Types;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class TraceStoreModuleTest {
    @Test
    void serverStorePhaseSatisfiesEagerApiUsageAndMaintenanceInjection() {
        var app = new WiringServerApp();

        app.initialize();

        assertInstanceOf(MongoTraceStore.class, app.bean(TraceStore.class));
        assertTrue(app.loadedModules.indexOf(TraceStoreModule.class) < app.loadedModules.indexOf(ApiUserModule.class));
        assertTrue(app.loadedModules.indexOf(ApiUserModule.class) < app.loadedModules.indexOf(TraceModule.class));
        assertNotNull(app.archive);
        assertNotNull(app.cleanup);
        var usage = app.usage.usage("owner", "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z");
        assertEquals(0L, usage.totalTokens);
        verify(app.traces).aggregate(any());
    }

    private static final class WiringServerApp extends ServerApp {
        private final MongoConfig mongo = mock(MongoConfig.class);
        @SuppressWarnings("unchecked")
        private final MongoCollection<Trace> traces = mock(MongoCollection.class);
        @SuppressWarnings("unchecked")
        private final MongoCollection<TraceDailyStats> stats = mock(MongoCollection.class);
        private final List<Class<?>> loadedModules = new ArrayList<>();
        private ApiUserUsageService usage;
        private TraceArchiveService archive;
        private StaleRunCleanupService cleanup;

        WiringServerApp() {
            context = new ModuleContext(new LogManager());
            when(mongo.collection(Trace.class)).thenAnswer(invocation -> context.bind(Types.generic(MongoCollection.class, Trace.class), null, traces));
            when(mongo.collection(TraceDailyStats.class))
                .thenAnswer(invocation -> context.bind(Types.generic(MongoCollection.class, TraceDailyStats.class), null, stats));
            when(traces.aggregate(any())).thenReturn(List.of());
        }

        @Override
        protected void load(Module module) {
            loadedModules.add(module.getClass());
            if (module instanceof TraceStoreModule) {
                super.load(module);
            } else if (module instanceof ApiUserModule) {
                usage = bind(ApiUserUsageService.class);
            } else if (module instanceof TraceModule) {
                context.bind(SpanStore.class, null, mock(SpanStore.class));
                context.bind(TracePayloadStore.class, null, mock(TracePayloadStore.class));
                context.bind(AgentRunStore.class, null, mock(AgentRunStore.class));
                archive = bind(new TraceArchiveService(mock(ObjectStorageServiceResolver.class), null, null));
                cleanup = bind(StaleRunCleanupService.class);
            }
        }

        @Override
        public <T extends Config> T config(Class<T> configClass) {
            return configClass.cast(mongo);
        }

        @Override
        public String requiredProperty(String key) {
            return "mongodb://localhost/startup-wiring-test";
        }

        @Override
        public void loadProperties(String classpath) {
        }

        @Override
        public void onStartup(Task task) {
        }
    }
}
