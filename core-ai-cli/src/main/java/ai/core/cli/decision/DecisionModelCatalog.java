package ai.core.cli.decision;

import ai.core.api.server.hubmodels.HubDecisionModelView;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.hub.model.HubDecisionModelClient;
import ai.core.cli.http.RemoteApiException;
import ai.core.decision.DecisionModelHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The decision models the logged-in server exposes, cached so session creation and tool descriptions do
 * not pay a round trip each time. Successful fetches live for 60 s; failures back off for 30 s and keep
 * the last-known-good list.
 *
 * @author stephen
 */
public class DecisionModelCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionModelCatalog.class);
    private static final long SUCCESS_TTL_MS = 60_000L;
    private static final long FAILURE_BACKOFF_MS = 30_000L;

    public static DecisionModelCatalog create(AuthConfig auth) {
        if (auth == null || auth.apiKey() == null || auth.serverUrl() == null) return null;
        var serverUrl = auth.serverUrl().replaceAll("/+$", "");
        return new DecisionModelCatalog(new HubDecisionModelClient(serverUrl, auth.apiKey(), auth.insecure()));
    }

    private final HubDecisionModelClient client;
    private final long successTtlMs;
    private final long failureBackoffMs;
    private final Object refreshLock = new Object();
    private volatile CompletableFuture<Void> refreshInFlight;
    private volatile Snapshot snapshot;
    private volatile long nextAttemptAt;

    DecisionModelCatalog(HubDecisionModelClient client) {
        this(client, SUCCESS_TTL_MS, FAILURE_BACKOFF_MS);
    }

    DecisionModelCatalog(HubDecisionModelClient client, long successTtlMs, long failureBackoffMs) {
        this.client = client;
        this.successTtlMs = successTtlMs;
        this.failureBackoffMs = failureBackoffMs;
    }

    /** Background first load; never blocks engine startup. */
    public void warmUp() {
        refreshFuture();
    }

    /** Waits at most {@code timeoutMs} for a first snapshot; later calls read the cached snapshot. */
    public void ensureLoaded(long timeoutMs) {
        if (snapshot != null) return;
        var task = refreshFuture();
        try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.debug("decision model catalog not ready: {}", e.getMessage());
        }
    }

    public List<DecisionModelHint> models() {
        return current().models();
    }

    public String defaultModel() {
        var models = models();
        return models.isEmpty() ? null : models.getFirst().modelId();
    }

    private Snapshot current() {
        var value = snapshot;
        if (value != null && System.currentTimeMillis() - value.fetchedAt() >= successTtlMs) {
            refreshFuture();
        }
        return value == null ? Snapshot.EMPTY : value;
    }

    private CompletableFuture<Void> refreshFuture() {
        var current = refreshInFlight;
        if (current != null) return current;
        synchronized (refreshLock) {
            current = refreshInFlight;
            if (current != null) return current;
            var task = CompletableFuture.runAsync(this::refresh);
            refreshInFlight = task;
            task.whenComplete((ignored, error) -> clearRefresh(task));
            return task;
        }
    }

    private void clearRefresh(CompletableFuture<Void> task) {
        synchronized (refreshLock) {
            if (task.equals(refreshInFlight)) refreshInFlight = null;
        }
    }

    void refresh() {
        synchronized (refreshLock) {
            var now = System.currentTimeMillis();
            if (now < nextAttemptAt) return;
            try {
                var response = client.models();
                snapshot = new Snapshot(hints(response.models), now);
                nextAttemptAt = now + successTtlMs;
                LOGGER.info("decision model catalog loaded: {}", snapshot.models().size());
            } catch (RemoteApiException e) {
                failed(e.statusCode == 401 ? "Authentication failed. Sign in again from Hub." : "HTTP " + e.statusCode, now);
            } catch (RuntimeException e) {
                failed(e.getMessage(), now);
            }
        }
    }

    private void failed(String error, long now) {
        var previous = snapshot;
        snapshot = new Snapshot(previous == null ? List.of() : previous.models(), now);
        nextAttemptAt = now + failureBackoffMs;
        LOGGER.warn("decision model catalog unavailable: {} (fallback: server default only)", error);
    }

    private List<DecisionModelHint> hints(List<HubDecisionModelView> models) {
        if (models == null || models.isEmpty()) return List.of();
        var hints = new ArrayList<DecisionModelHint>(models.size());
        for (var model : models) {
            if (model.modelId != null) {
                hints.add(new DecisionModelHint(model.modelId, model.displayName, model.providerName, model.upstreamModel));
            }
        }
        return List.copyOf(hints);
    }

    private record Snapshot(List<DecisionModelHint> models, long fetchedAt) {
        private static final Snapshot EMPTY = new Snapshot(List.of(), 0);
    }
}
