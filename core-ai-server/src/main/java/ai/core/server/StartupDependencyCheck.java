package ai.core.server;

import core.framework.db.Database;
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
 * Startup probes for the stores that are actually configured. PostgreSQL is the business store in
 * every FBR environment; MongoDB is optional and is only probed while the legacy store is still
 * enabled. Each probe runs on its own virtual thread with a hard timeout because the clients may
 * block far longer than their configured socket timeout.
 *
 * @author stephen
 */
public final class StartupDependencyCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger(StartupDependencyCheck.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Database database;
    private final Mongo mongo;

    public StartupDependencyCheck(Database database, Mongo mongo) {
        this.database = database;
        this.mongo = mongo;
    }

    public void verify() {
        if (database == null && mongo == null) {
            throw new Error("no store dependency is configured");
        }
        if (database != null) verify("postgres", this::pingPostgres);
        if (mongo != null) verify("mongo", this::pingMongo);
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

    private boolean pingPostgres() {
        var row = database.selectOne("SELECT 1 AS ok", PgStartupRow.class).orElse(null);
        return row != null && row.ok != null && row.ok.intValue() == 1;
    }

    private boolean pingMongo() {
        return mongo.runCommand(new Document("ping", 1)) != null;
    }
}
