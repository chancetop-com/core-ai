package ai.core.cli.media;

import ai.core.api.server.hubmodels.HubMediaModelView;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.http.RemoteApiException;
import ai.core.tool.tools.MediaModelHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The media models the logged-in server enables, cached so session creation and tool descriptions do not
 * pay a round trip each time. Successful fetches live for 60 s; failures back off for 30 s and keep the
 * last-known-good lists, so a briefly unreachable server does not hide the media tools mid-session.
 *
 * @author stephen
 */
public class MediaModelCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaModelCatalog.class);
    private static final long SUCCESS_TTL_MS = 60_000L;
    private static final long FAILURE_BACKOFF_MS = 30_000L;

    public static MediaModelCatalog create(AuthConfig auth) {
        if (auth == null || auth.apiKey() == null || auth.serverUrl() == null) return null;
        var serverUrl = auth.serverUrl().replaceAll("/+$", "");
        return new MediaModelCatalog(new HubMediaModelClient(serverUrl, auth.apiKey(), auth.insecure()));
    }

    private final HubMediaModelClient client;
    private final long successTtlMs;
    private final long failureBackoffMs;
    private final Object refreshLock = new Object();
    private volatile CompletableFuture<Void> refreshInFlight;
    private volatile Snapshot snapshot;
    private volatile long nextAttemptAt;

    MediaModelCatalog(HubMediaModelClient client) {
        this(client, SUCCESS_TTL_MS, FAILURE_BACKOFF_MS);
    }

    MediaModelCatalog(HubMediaModelClient client, long successTtlMs, long failureBackoffMs) {
        this.client = client;
        this.successTtlMs = successTtlMs;
        this.failureBackoffMs = failureBackoffMs;
    }

    /** Background first load; never blocks engine startup. */
    public void warmUp() {
        refreshAsync();
    }

    /**
     * Waits at most {@code timeoutMs} for a first snapshot. Only the first session on a cold engine uses
     * this; later calls read the cached snapshot.
     */
    public void ensureLoaded(long timeoutMs) {
        if (snapshot != null) return;
        var task = refreshFuture();
        try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.debug("media model catalog not ready: {}", e.getMessage());
        }
    }

    public List<MediaModelHint> imageModels() {
        return current().imageModels();
    }

    public List<MediaModelHint> imageEditModels() {
        return current().imageEditModels();
    }

    public List<MediaModelHint> videoModels() {
        return current().videoModels();
    }

    public String defaultImageModel() {
        return first(imageModels());
    }

    public String defaultImageEditModel() {
        return first(imageEditModels());
    }

    public String defaultVideoModel() {
        return first(videoModels());
    }

    /** Fetch failure description, null after a successful load. */
    public String error() {
        return snapshot == null ? null : snapshot.error();
    }

    /** True when the server answered 404 — the catalog endpoint predates this client. */
    public boolean unavailable() {
        return snapshot != null && snapshot.unavailable();
    }

    public boolean hasSnapshot() {
        return snapshot != null;
    }

    private String first(List<MediaModelHint> models) {
        return models.isEmpty() ? null : models.getFirst().modelId();
    }

    private Snapshot current() {
        var value = snapshot;
        if (value != null && System.currentTimeMillis() - value.fetchedAt() >= successTtlMs) {
            refreshAsync();
        }
        return value == null ? Snapshot.EMPTY : value;
    }

    private void refreshAsync() {
        refreshFuture();
    }

    /**
     * One refresh at a time: callers that arrive while a fetch is in flight join it instead of spawning
     * another task that would only block on the same lock. This keeps a slow or unreachable server from
     * accumulating one stalled thread per settings read or session build.
     */
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
                snapshot = new Snapshot(hints(response.imageModels), hints(response.imageEditModels),
                        hints(response.videoModels), null, false, now);
                nextAttemptAt = now + successTtlMs;
                LOGGER.info("media model catalog loaded: image={}, imageEdit={}, video={}",
                        snapshot.imageModels().size(), snapshot.imageEditModels().size(), snapshot.videoModels().size());
            } catch (RemoteApiException e) {
                failed(e.statusCode == 404, catalogError(e), now);
            } catch (RuntimeException e) {
                failed(false, e.getMessage(), now);
            }
        }
    }

    private String catalogError(RemoteApiException e) {
        if (e.statusCode == 401) return "Authentication failed. Sign in again from Hub.";
        return "HTTP " + e.statusCode + ": " + e.getMessage();
    }

    private void failed(boolean unavailable, String error, long now) {
        var previous = snapshot;
        snapshot = new Snapshot(previous == null ? List.of() : previous.imageModels(),
                previous == null ? List.of() : previous.imageEditModels(),
                previous == null ? List.of() : previous.videoModels(),
                error == null ? "media model catalog request failed" : error, unavailable, now);
        nextAttemptAt = now + failureBackoffMs;
        LOGGER.warn("media model catalog unavailable: {} (fallback: configured defaults only)", error);
    }

    private List<MediaModelHint> hints(List<HubMediaModelView> models) {
        if (models == null || models.isEmpty()) return List.of();
        var hints = new ArrayList<MediaModelHint>(models.size());
        for (var model : models) {
            if (model.modelId != null) {
                hints.add(new MediaModelHint(model.modelId, model.upstreamModel, model.providerName, model.displayName));
            }
        }
        return List.copyOf(hints);
    }

    private record Snapshot(List<MediaModelHint> imageModels, List<MediaModelHint> imageEditModels,
                            List<MediaModelHint> videoModels, String error, boolean unavailable, long fetchedAt) {
        private static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), List.of(), null, false, 0);
    }
}
