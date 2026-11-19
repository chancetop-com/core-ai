package ai.core.server;

import core.framework.mongo.Mongo;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * MongoDB still backs the remaining legacy collections, so a startup that cannot reach it must
 * fail instead of leaving a half initialized instance behind. The probe runs on its own virtual
 * thread with a hard timeout because the client may block far longer than its socket timeout.
 *
 * @author stephen
 */
public final class StartupDependencyCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger(StartupDependencyCheck.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Mongo mongo;

    public StartupDependencyCheck(Mongo mongo) {
        this.mongo = mongo;
    }

    public void verify() {
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

    private boolean pingMongo() {
        return mongo.runCommand(new Document("ping", 1)) != null;
    }
}
