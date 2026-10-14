package ai.core.server;

import ai.core.api.server.runtime.RuntimeWebService;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.run.AgentRunner;
import ai.core.server.runtime.AdmissionInterceptor;
import ai.core.server.runtime.RuntimeCapacityGuard;
import ai.core.server.runtime.RuntimeLimitsProvider;
import ai.core.server.runtime.RuntimeSampleService;
import ai.core.server.runtime.RuntimeSettingsService;
import ai.core.server.runtime.RuntimeStatsJob;
import ai.core.server.runtime.RuntimeStatsService;
import ai.core.server.runtime.RuntimeWebServiceImpl;
import ai.core.server.sandbox.SandboxManager;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.UserSessionBudget;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.trace.spi.LocalSpanProcessor;
import ai.core.session.SessionRuntimeLimits;
import core.framework.module.Module;
import redis.clients.jedis.JedisPool;

import java.time.Duration;

/**
 * Wires the runtime governance pieces: the hot-reloaded limits, the capacity guard, the entry
 * admission interceptor and the limits/stats admin API. Loads after the modules that own the
 * counters it reads (messaging, sessions, runs, sandbox) and after WebFoundationModule so the
 * admission interceptor sits innermost in the chain — authentication and permissions still answer
 * before a saturated platform refuses the call.
 *
 * @author stephen
 */
public class RuntimeGovernanceModule extends Module {
    @Override
    protected void initialize() {
        var limitsProvider = new RuntimeLimitsProvider(bean(SystemSettingsService.class));
        bind(limitsProvider);
        var guard = new RuntimeCapacityGuard(limitsProvider, bean(TurnStateRegistry.class));
        bind(guard);
        http().intercept(new AdmissionInterceptor(guard));
        // sandbox quotas are read live from system settings by the manager SandboxModule binds
        bean(SandboxManager.class).limitsSupplier = limitsProvider::get;
        // run admission reads the same limits; assigned after AgentRunnerModule is bound
        bean(AgentRunner.class).admission.limitsSupplier = limitsProvider::get;
        // session budgets are read live by the session layer itself (queue cap) and by the turn
        // watchdog; wiring the source once keeps both out of the per-message path
        SessionRuntimeLimits.configure(() -> {
            var limits = guard.limits();
            return new SessionRuntimeLimits(limits.maxQueuedMessagesPerSession, limits.turnTimeoutSeconds);
        });
        // per-user session budget: counted in Redis so it holds across replicas
        var userSessions = new UserSessionBudget(bean(JedisPool.class));
        userSessions.limitsSupplier = limitsProvider::get;
        bean(AgentSessionManager.class).userSessionBudget = userSessions;
        // span ingest queue depth is fixed by the pool that backs it, so it is read once at startup
        onStartup(() -> LocalSpanProcessor.configureCapacity(limitsProvider.get().spanIngestQueueCapacity));
        bind(RuntimeSettingsService.class);
        bind(RuntimeStatsService.class);
        bind(RuntimeSampleService.class);
        // gauges into the action log (Kibana), so watermarks and refusals are visible without the UI
        schedule().fixedRate("runtime-stats", bind(RuntimeStatsJob.class), Duration.ofMinutes(1));
        api().service(RuntimeWebService.class, bind(RuntimeWebServiceImpl.class));
    }
}
