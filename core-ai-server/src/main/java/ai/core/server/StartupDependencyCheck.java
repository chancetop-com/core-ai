package ai.core.server;

import core.framework.mongo.Mongo;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * The server cannot serve a single request without redis (command bus, session ownership) and mongo,
 * so a startup that cannot reach them must fail instead of leaving a half initialized instance behind.
 * Each probe runs on its own virtual thread with a hard timeout, the jedis and mongo clients may block
 * far longer than their configured socket timeout while resolving or selecting a server.
 *
 * @author stephen
 */
public final class StartupDependencyCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger(StartupDependencyCheck.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final JedisPool jedisPool;
    private final Mongo mongo;

    public StartupDependencyCheck(JedisPool jedisPool, Mongo mongo) {
        this.jedisPool = jedisPool;
        this.mongo = mongo;
    }

    public void verify() {
        verify("redis", this::pingRedis);
        verify("mongo", this::pingMongo);
    }

    private void verify(String name, BooleanSupplier probe) {
        var result = new CompletableFuture<Boolean>();
        Thread.ofVirtual().name("startup-dependency-check-" + name).start(() -> {
            try {
                result.complete(probe.getAsBoolean());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        try {
            if (!result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new Error("startup dependency not ready, name=" + name);
            }
            LOGGER.info("startup dependency verified, name={}", name);
        } catch (TimeoutException e) {
            throw new Error("startup dependency check timed out, name=" + name + ", timeout=" + TIMEOUT, e);
        } catch (ExecutionException e) {
            throw new Error("startup dependency check failed, name=" + name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Error("startup dependency check interrupted, name=" + name, e);
        }
    }

    private boolean pingRedis() {
        try (var jedis = jedisPool.getResource()) {
            return "PONG".equals(jedis.ping());
        }
    }

    private boolean pingMongo() {
        return mongo.runCommand(new Document("ping", 1)) != null;
    }
}
