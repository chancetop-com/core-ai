package ai.core.server;

import ai.core.server.a2a.A2AEventRelay;
import ai.core.server.a2a.A2ATaskRegistry;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.JedisConfig;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.schedule.MongoScheduledTaskStore;
import core.framework.module.Module;
import redis.clients.jedis.JedisPool;

/**
 * @author stephen
 */
class MessagingInfrastructureModule extends Module {
    @Override
    protected void initialize() {
        var redisHost = property("sys.jedis.host").orElse("localhost");
        var redisPort = Integer.parseInt(property("sys.jedis.port").orElse("6379"));
        var jedisPool = new JedisConfig(redisHost, redisPort).createJedisPool();
        var ownershipRegistry = bind(new SessionOwnershipRegistry(jedisPool));
        var turnStateRegistry = bind(new TurnStateRegistry(jedisPool));
        onStartup(turnStateRegistry::start);
        onShutdown(turnStateRegistry::stop);
        bind(new EventPublisher(jedisPool));
        bind(new A2ATaskRegistry(jedisPool, ownershipRegistry));
        bind(new A2AEventRelay(jedisPool));
        bind(new RpcClient(jedisPool, ownershipRegistry));
        bind(JedisPool.class, jedisPool);
        onShutdown(jedisPool::close);
        // bound early so session/tool assembly paths (AgentSessionManager, ToolRegistryService)
        // can @Inject the store before SessionSchedulerModule loads
        bind(MongoScheduledTaskStore.class);
    }
}
