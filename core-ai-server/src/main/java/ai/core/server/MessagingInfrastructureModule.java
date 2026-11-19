package ai.core.server;

import ai.core.server.a2a.A2AEventRelay;
import ai.core.server.a2a.A2ATaskRegistry;
import ai.core.server.a2a.PgA2ATaskSnapshotStore;
import ai.core.server.messaging.HttpRpcTransport;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.LeaseCleanupJob;
import ai.core.server.messaging.PgOwnershipStore;
import ai.core.server.messaging.PgTurnStateStore;
import ai.core.server.messaging.PodRegistry;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.session.PgSessionBudgetStore;
import ai.core.server.session.SessionBudgetStore;
import ai.core.server.state.StateExpiryCleanupJob;
import ai.core.server.state.StateSchemaCheck;
import ai.core.server.web.PodLocalExecutor;
import ai.core.server.web.sse.ChannelService;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.server.web.sse.SessionEventHubRegistry;
import core.framework.module.Module;

import java.time.Duration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author stephen
 */
class MessagingInfrastructureModule extends Module {
    @Override
    @SuppressFBWarnings("WEM_OBSCURING_EXCEPTION")
    protected void initialize() {
        var storeMode = property("sys.store.mode").orElse("mongo");
        if (!"pg".equals(storeMode)) {
            throw new IllegalArgumentException("the Redis messaging backends were removed; sys.store.mode=pg is required");
        }
        var internalToken = property("sys.internal.token").orElse("");
        // declared in sys.properties; must be read so core-ng accepts the config
        property("sys.internal.token.previous");
        if (internalToken.isBlank()) {
            throw new IllegalArgumentException("sys.internal.token is required");
        }

        var ownershipRegistry = bind(new SessionOwnershipRegistry(bind(PgOwnershipStore.class)));
        var podRegistry = bind(new PodRegistry(ownershipRegistry.getHostname(), podAddress(ownershipRegistry.getHostname())));
        onStartup(podRegistry::start);
        onShutdown(podRegistry::stop);
        schedule().fixedRate("lease-cleanup", bind(LeaseCleanupJob.class), Duration.ofMinutes(1));

        var turnStateRegistry = bind(new TurnStateRegistry(bind(PgTurnStateStore.class), podRegistry.podId()));
        onStartup(turnStateRegistry::start);
        onShutdown(turnStateRegistry::stop);
        bind(SessionBudgetStore.class, bind(PgSessionBudgetStore.class));

        var hubRegistry = bind(new SessionEventHubRegistry());
        bind(new EventPublisher(hubRegistry, ownershipRegistry, podRegistry, internalToken));
        var sessionChannelService = bind(new SessionChannelService(hubRegistry, ownershipRegistry,
                podRegistry, internalToken, bean(ChannelService.class)));
        onStartup(sessionChannelService::start);
        onShutdown(sessionChannelService::stop);

        bind(new A2ATaskRegistry(bind(PgA2ATaskSnapshotStore.class), ownershipRegistry));
        bind(new A2AEventRelay(podRegistry, internalToken));
        bind(new RpcClient(new HttpRpcTransport(podRegistry::address, internalToken)));
        // generic "run locally when this pod owns the session, otherwise RPC to the owner" helper; bound
        // here so modules loaded before MessagingRuntimeModule can inject it
        bind(PodLocalExecutor.class);

        var schemaCheck = bind(StateSchemaCheck.class);
        onStartup(schemaCheck::verify);
        schedule().fixedRate("state-expiry-cleanup", bind(StateExpiryCleanupJob.class), Duration.ofMinutes(15));
    }

    private String podAddress(String podId) {
        var ip = System.getenv("POD_IP");
        var port = property("sys.http.listen").orElse("8080");
        return (ip == null || ip.isBlank() ? podId : ip) + ":" + port;
    }
}
