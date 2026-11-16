package ai.core.server;

import ai.core.server.a2a.A2AEventRelay;
import ai.core.server.a2a.A2ATaskRegistry;
import ai.core.server.a2a.A2ATaskSnapshotStore;
import ai.core.server.a2a.PgA2ATaskSnapshotStore;
import ai.core.server.a2a.RedisA2ATaskSnapshotStore;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.JedisConfig;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.state.StateBackend;
import ai.core.server.state.StateExpiryCleanupJob;
import ai.core.server.state.StateSchemaCheck;
import ai.core.server.web.PodLocalExecutor;
import core.framework.module.Module;
import redis.clients.jedis.JedisPool;

import java.time.Duration;

/**
 * @author stephen
 */
class MessagingInfrastructureModule extends Module {
    @Override
    protected void initialize() {
        var redisHost = property("sys.jedis.host").orElse("localhost");
        var redisPort = Integer.parseInt(property("sys.jedis.port").orElse("6379"));
        var jedisPool = new JedisConfig(redisHost, redisPort).createJedisPool();
        var stateBackend = StateBackend.of(property("sys.state.backend").orElse("redis"),
                property("sys.store.mode").orElse("mongo"));
        var ownershipRegistry = bind(new SessionOwnershipRegistry(jedisPool));
        var turnStateRegistry = bind(new TurnStateRegistry(jedisPool));
        onStartup(turnStateRegistry::start);
        onShutdown(turnStateRegistry::stop);
        bind(new EventPublisher(jedisPool));
        bind(new A2ATaskRegistry(a2aTaskStore(jedisPool, stateBackend), ownershipRegistry));
        bind(new A2AEventRelay(jedisPool));
        bind(new RpcClient(jedisPool, ownershipRegistry));
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

    private A2ATaskSnapshotStore a2aTaskStore(JedisPool jedisPool, StateBackend backend) {
        if (backend == StateBackend.POSTGRES) return bind(new PgA2ATaskSnapshotStore());
        return new RedisA2ATaskSnapshotStore(jedisPool);
    }
}

