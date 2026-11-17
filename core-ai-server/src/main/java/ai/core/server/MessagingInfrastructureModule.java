package ai.core.server;

import ai.core.server.a2a.A2AEventRelay;
import ai.core.server.a2a.A2ATaskRegistry;
import ai.core.server.a2a.A2ATaskSnapshotStore;
import ai.core.server.a2a.PgA2ATaskSnapshotStore;
import ai.core.server.a2a.RedisA2ATaskSnapshotStore;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.HttpRpcTransport;
import ai.core.server.messaging.JedisConfig;
import ai.core.server.messaging.LeaseCleanupJob;
import ai.core.server.messaging.PgOwnershipStore;
import ai.core.server.messaging.PgTurnStateStore;
import ai.core.server.messaging.PodRegistry;
import ai.core.server.messaging.RedisRpcTransport;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.RpcMode;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.session.PgSessionBudgetStore;
import ai.core.server.session.RedisSessionBudgetStore;
import ai.core.server.session.SessionBudgetStore;
import ai.core.server.state.StateBackend;
import ai.core.server.state.StateExpiryCleanupJob;
import ai.core.server.state.StateSchemaCheck;
import ai.core.server.web.PodLocalExecutor;
import core.framework.module.Module;
import redis.clients.jedis.JedisPool;

import java.time.Duration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author stephen
 */
class MessagingInfrastructureModule extends Module {
    @Override
    @SuppressFBWarnings("WEM_OBSCURING_EXCEPTION")
    protected void initialize() {
        var redisHost = property("sys.jedis.host").orElse("localhost");
        var redisPort = Integer.parseInt(property("sys.jedis.port").orElse("6379"));
        var jedisPool = new JedisConfig(redisHost, redisPort).createJedisPool();
        var storeMode = property("sys.store.mode").orElse("mongo");
        var stateBackend = StateBackend.of(property("sys.state.backend").orElse("redis"), storeMode, "sys.state.backend");
        var leaseBackend = StateBackend.of(property("sys.lease.backend").orElse("redis"), storeMode, "sys.lease.backend");
        var rpcMode = RpcMode.of(property("sys.rpc.transport").orElse("redis"));
        var internalToken = property("sys.internal.token").orElse("");
        // declared in sys.properties; must be read even when RPC uses Redis so core-ng accepts the config
        property("sys.internal.token.previous");
        if (rpcMode == RpcMode.HTTP && internalToken.isBlank()) {
            throw new IllegalArgumentException("sys.rpc.transport=http requires sys.internal.token");
        }

        var ownershipRegistry = ownershipRegistry(jedisPool, leaseBackend);
        var podRegistry = podRegistry(leaseBackend, rpcMode, ownershipRegistry);
        var turnStateRegistry = turnStateRegistry(jedisPool, leaseBackend, podRegistry);
        onStartup(turnStateRegistry::start);
        onShutdown(turnStateRegistry::stop);
        bind(new EventPublisher(jedisPool));
        bind(new A2ATaskRegistry(a2aTaskStore(jedisPool, stateBackend), ownershipRegistry));
        bind(new A2AEventRelay(jedisPool));
        var httpRpcTransport = rpcMode == RpcMode.HTTP ? new HttpRpcTransport(podRegistry::address, internalToken) : null;
        bind(new RpcClient(ownershipRegistry, new RedisRpcTransport(jedisPool), httpRpcTransport));
        // generic "run locally when this pod owns the session, otherwise RPC to the owner" helper; bound
        // here so modules loaded before MessagingRuntimeModule can inject it
        bind(PodLocalExecutor.class);
        bind(JedisPool.class, jedisPool);
        onShutdown(jedisPool::close);
        if (stateBackend == StateBackend.POSTGRES) {
            var schemaCheck = bind(StateSchemaCheck.class);
            onStartup(schemaCheck::verify);
            schedule().fixedRate("state-expiry-cleanup", bind(StateExpiryCleanupJob.class), Duration.ofMinutes(15));
        }
    }

    private SessionOwnershipRegistry ownershipRegistry(JedisPool jedisPool, StateBackend leaseBackend) {
        if (leaseBackend == StateBackend.POSTGRES) {
            return bind(new SessionOwnershipRegistry(bind(PgOwnershipStore.class)));
        }
        return bind(new SessionOwnershipRegistry(jedisPool));
    }

    private PodRegistry podRegistry(StateBackend leaseBackend, RpcMode rpcMode, SessionOwnershipRegistry ownershipRegistry) {
        if (leaseBackend != StateBackend.POSTGRES && rpcMode != RpcMode.HTTP) return null;
        var podRegistry = bind(new PodRegistry(ownershipRegistry.getHostname(), podAddress(ownershipRegistry.getHostname())));
        onStartup(podRegistry::start);
        onShutdown(podRegistry::stop);
        schedule().fixedRate("lease-cleanup", bind(new LeaseCleanupJob(leaseBackend == StateBackend.POSTGRES)), Duration.ofMinutes(1));
        return podRegistry;
    }

    private TurnStateRegistry turnStateRegistry(JedisPool jedisPool, StateBackend leaseBackend, PodRegistry podRegistry) {
        if (leaseBackend == StateBackend.POSTGRES) {
            bind(SessionBudgetStore.class, bind(PgSessionBudgetStore.class));
            return bind(new TurnStateRegistry(bind(PgTurnStateStore.class), podRegistry.podId()));
        }
        bind(SessionBudgetStore.class, new RedisSessionBudgetStore(jedisPool));
        return bind(new TurnStateRegistry(jedisPool));
    }

    private A2ATaskSnapshotStore a2aTaskStore(JedisPool jedisPool, StateBackend backend) {
        if (backend == StateBackend.POSTGRES) return bind(new PgA2ATaskSnapshotStore());
        return new RedisA2ATaskSnapshotStore(jedisPool);
    }

    private String podAddress(String podId) {
        var ip = System.getenv("POD_IP");
        var port = property("sys.http.listen").orElse("8080");
        return (ip == null || ip.isBlank() ? podId : ip) + ":" + port;
    }
}
