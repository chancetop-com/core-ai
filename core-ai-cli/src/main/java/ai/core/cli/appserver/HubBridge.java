package ai.core.cli.appserver;

import ai.core.api.server.hubcatalog.HubCatalogResponse;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.hub.catalog.CatalogHubClient;
import ai.core.cli.hub.skill.SkillHubClient;
import ai.core.cli.hub.skill.SkillHubMarker;
import ai.core.cli.hub.skill.SkillInstallConflictException;
import ai.core.cli.hub.skill.SkillInstallResolver;
import ai.core.cli.hub.skill.SkillInstaller;
import ai.core.cli.hub.skill.SkillLocations;
import ai.core.cli.hub.skill.SkillUpdater;
import ai.core.cli.http.RemoteApiException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server capability bridge behind the desktop Hub panel: the one-shot capability catalog
 * (MCP tools / API operations / agents / LLM_CALL definitions), the skill catalog, and skill
 * pull into the user skill root ({@code ~/.core-ai/skills/{namespace}/{name}} — the same
 * managed install the CLI writes). Reads are answerable without a server (a
 * {@code connected:false} envelope drives the panel's sign-in guidance); a pull is an action
 * and fails with a business error instead.
 *
 * @author stephen
 */
public class HubBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(HubBridge.class);
    private static final int DEFAULT_SKILL_LIMIT = 200;
    private static final int MAX_SKILL_LIMIT = 200;
    /** The hub panels are re-opened often; answers this recent are served without another round trip. */
    private static final long CACHE_TTL_MILLIS = 120_000L;
    /** A panel that waits longer than this reads as broken — the catalog normally takes a couple of seconds. */
    private static final Duration HUB_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ARCHIVE_TIMEOUT = Duration.ofSeconds(120);
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "hub-cache-refresh");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Installs a fetched archive; package-visible so tests can drive the install paths against a
     * temp directory without a server. The target is the directory already linked to the skill
     * (a pushed working copy carries the marker), falling back to {@code {namespace}/{name}}.
     * A conflict the user could resolve by confirming comes back as {@code SKILL_CONFLICT},
     * anything else as {@code SKILL_PULL_FAILED}.
     */
    static ObjectNode install(Path baseDir, String serverUrl, String namespace, String name,
                              SkillHubClient.ArchiveResult archive, boolean force) {
        var qualified = namespace + "/" + name;
        var targetDir = new SkillInstallResolver().resolveTarget(baseDir, serverUrl, archive.id(), qualified);
        var digest = archive.digest() == null ? "" : archive.digest();
        var source = SkillHubMarker.Marker.pulled(qualified, archive.id(), digest, serverUrl);
        try {
            var outcome = new SkillInstaller().install(targetDir, archive.bytes(), source, force);
            var node = Params.object();
            node.put("status", outcome.replaced() ? "installed" : "up_to_date");
            node.put("qualifiedName", qualified);
            if (archive.id() != null) node.put("id", archive.id());
            node.put("digest", digest);
            node.put("dir", outcome.dir().toAbsolutePath().toString());
            node.put("files", outcome.files());
            return node;
        } catch (SkillInstallConflictException e) {
            throw RpcException.business("SKILL_CONFLICT", e.getMessage(), e);
        } catch (RuntimeException e) {
            throw RpcException.business("SKILL_PULL_FAILED", messageOf(e), e);
        }
    }

    private static void addSections(ObjectNode node, HubCatalogResponse response) {
        var sections = node.putArray("sections");
        for (var section : nullSafe(response.sections)) {
            sections.add(section);
        }
    }

    private static void addSources(ObjectNode node, HubCatalogResponse response) {
        var sources = node.putArray("sources");
        for (var source : nullSafe(response.sources)) {
            var item = sources.addObject();
            item.put("kind", source.kind);
            item.put("name", source.name);
            if (source.state != null) item.put("state", source.state);
            if (source.count != null) item.put("count", source.count);
            if (source.stale != null) item.put("stale", source.stale);
        }
    }

    static void addTools(ObjectNode node, HubCatalogResponse response) {
        var tools = node.putArray("tools");
        for (var tool : nullSafe(response.tools)) {
            var item = tools.addObject();
            item.put("kind", tool.kind);
            item.put("name", tool.name);
            item.put("path", tool.path);
            if (tool.refId != null) item.put("refId", tool.refId);
            if (tool.group != null) item.put("group", tool.group);
            if (tool.description != null) item.put("description", tool.description);
            if (tool.stale != null) item.put("stale", tool.stale);
        }
    }

    private static ObjectNode notConnected(ObjectNode node, String reason) {
        node.put("connected", false);
        node.put("reason", reason);
        return node;
    }

    /** A rejected request (401) is a credential problem, not an unreachable server — the panel prompts a sign-in. */
    private static ObjectNode unreachable(ObjectNode node, RemoteApiException e) {
        if (e.statusCode == 401) {
            return notConnected(node, "not_logged_in").put("message", e.getMessage());
        }
        LOGGER.warn("hub request failed: HTTP {} {}", e.statusCode, e.getMessage());
        return notConnected(node, "unreachable").put("message", "HTTP " + e.statusCode + ": " + e.getMessage());
    }

    /**
     * A TLS failure gets its own reason: the desktop turns it into the "trust this server's
     * certificate" action instead of a generic unreachable server.
     */
    private static ObjectNode tlsFailure(ObjectNode node, RuntimeException e) {
        LOGGER.warn("hub request failed: {}", e.getMessage());
        return notConnected(node, "untrusted_certificate").put("message", messageOf(e));
    }

    static ObjectNode failure(ObjectNode node, String what, RuntimeException e) {
        LOGGER.warn("{} failed: {}", what, e.getMessage());
        if (isCertificateFailure(e)) {
            return tlsFailure(node, e);
        }
        return notConnected(node, "unreachable").put("message", messageOf(e));
    }

    private static boolean isCertificateFailure(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException || cause instanceof CertificateException) {
                return true;
            }
        }
        return false;
    }

    private static String messageOf(RuntimeException e) {
        var message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Credentials credentials() {
        var auth = AuthConfig.load();
        if (auth == null || auth.apiKey() == null || auth.serverUrl() == null) {
            return null;
        }
        return new Credentials(auth.serverUrl(), auth.apiKey(), auth.insecure());
    }

    private static boolean fresh(long fetchedAt) {
        return System.currentTimeMillis() - fetchedAt < CACHE_TTL_MILLIS;
    }

    private static boolean answered(ObjectNode node) {
        return node.path("connected").asBoolean(false);
    }

    private volatile CachedCatalog catalogCache;
    private volatile CachedSkills skillsCache;
    private final AtomicBoolean catalogRefreshing = new AtomicBoolean();
    private final AtomicBoolean skillsRefreshing = new AtomicBoolean();

    /** Whether a login exists, i.e. a hub check is possible at all (it may still fail). */
    public boolean signedIn() {
        return credentials() != null;
    }

    public ObjectNode catalog() {
        return catalog(false);
    }

    /** {@code refresh} bypasses the cache (the panel's explicit Refresh action). */
    public ObjectNode catalog(boolean refresh) {
        var credentials = credentials();
        if (credentials == null) {
            return notConnected(Params.object(), "not_logged_in");
        }
        var cached = catalogCache;
        if (!refresh && cached != null && cached.credentials().equals(credentials)) {
            if (!fresh(cached.fetchedAt())) {
                refreshCatalogInBackground(credentials);
            }
            return cached.node();
        }
        var node = fetchCatalog(credentials);
        if (answered(node)) {
            catalogCache = new CachedCatalog(credentials, System.currentTimeMillis(), node);
        }
        return node;
    }

    private ObjectNode fetchCatalog(Credentials credentials) {
        var node = Params.object();
        try {
            var response = new CatalogHubClient(credentials.serverUrl(), credentials.apiKey(), credentials.insecure(), HUB_TIMEOUT)
                    .catalog();
            node.put("connected", true);
            node.put("serverUrl", credentials.serverUrl());
            if (response.generatedAt != null) {
                node.put("generatedAt", response.generatedAt);
            }
            addSections(node, response);
            addSources(node, response);
            addTools(node, response);
            return node;
        } catch (RemoteApiException e) {
            return unreachable(node, e);
        } catch (RuntimeException e) {
            return failure(node, "hub catalog fetch", e);
        }
    }

    /** The catalog and skill list are independent network calls; fetch them together for the Hub panel. */
    public ObjectNode overview(ObjectNode params) {
        boolean refresh = params.path("refresh").asBoolean(false);
        var catalogFuture = CompletableFuture.supplyAsync(() -> catalog(refresh), REFRESH_EXECUTOR);
        var skillsFuture = CompletableFuture.supplyAsync(() -> skills(Params.object().put("limit", DEFAULT_SKILL_LIMIT), refresh),
                REFRESH_EXECUTOR);
        var node = Params.object();
        node.set("catalog", catalogFuture.join());
        node.set("skills", skillsFuture.join());
        return node;
    }

    public ObjectNode skills(ObjectNode params) {
        return skills(params, params.path("refresh").asBoolean(false));
    }

    private ObjectNode skills(ObjectNode params, boolean refresh) {
        var credentials = credentials();
        if (credentials == null) {
            return notConnected(Params.object(), "not_logged_in");
        }
        var query = Params.optionalText(params, "query");
        int limit = Math.max(1, Math.min(Params.intParam(params, "limit", DEFAULT_SKILL_LIMIT), MAX_SKILL_LIMIT));
        boolean cacheable = query == null && limit == DEFAULT_SKILL_LIMIT;
        var cached = skillsCache;
        if (cacheable && !refresh && cached != null && cached.credentials().equals(credentials)) {
            if (!fresh(cached.fetchedAt())) {
                refreshSkillsInBackground(credentials);
            }
            return cached.node();
        }
        var fetched = fetchSkills(credentials, query, limit);
        if (cacheable && fetched.connected()) {
            skillsCache = new CachedSkills(credentials, System.currentTimeMillis(), fetched.node(), fetched.digests());
        }
        return fetched.node();
    }

    /**
     * Server digests for the skills panel's freshness column. Reuses the skill list the Hub panel just
     * fetched instead of issuing the same search again; null digests mean the server could not be read.
     */
    public SkillDigests skillDigests() {
        var credentials = credentials();
        if (credentials == null) {
            return new SkillDigests(null, false);
        }
        var cached = skillsCache;
        if (cached != null && cached.credentials().equals(credentials)) {
            if (!fresh(cached.fetchedAt())) {
                refreshSkillsInBackground(credentials);
            }
            return new SkillDigests(cached.digests(), true);
        }
        var fetched = fetchSkills(credentials, null, DEFAULT_SKILL_LIMIT);
        if (!fetched.connected()) {
            return new SkillDigests(null, true);
        }
        skillsCache = new CachedSkills(credentials, System.currentTimeMillis(), fetched.node(), fetched.digests());
        return new SkillDigests(fetched.digests(), true);
    }

    private SkillsFetch fetchSkills(Credentials credentials, String query, int limit) {
        var node = Params.object();
        try {
            var response = new SkillHubClient(credentials.serverUrl(), credentials.apiKey(), HUB_TIMEOUT, ARCHIVE_TIMEOUT,
                    credentials.insecure()).search(query, null, null, limit);
            node.put("connected", true);
            node.put("serverUrl", credentials.serverUrl());
            var digests = new HashMap<String, String>();
            var array = node.putArray("skills");
            for (var skill : nullSafe(response.skills)) {
                var item = array.addObject();
                item.put("qualifiedName", skill.qualifiedName);
                item.put("namespace", skill.namespace);
                item.put("name", skill.name);
                if (skill.description != null) item.put("description", skill.description);
                if (skill.sourceType != null) item.put("sourceType", skill.sourceType);
                if (skill.resourceCount != null) item.put("resourceCount", skill.resourceCount);
                if (skill.updatedAt != null) item.put("updatedAt", skill.updatedAt.toInstant().toString());
                if (skill.qualifiedName != null && skill.digest != null) {
                    digests.put(skill.qualifiedName, skill.digest);
                }
            }
            return new SkillsFetch(node, digests, true);
        } catch (RemoteApiException e) {
            return new SkillsFetch(unreachable(node, e), null, false);
        } catch (RuntimeException e) {
            return new SkillsFetch(failure(node, "hub skill list", e), null, false);
        }
    }

    private void refreshCatalogInBackground(Credentials credentials) {
        if (!catalogRefreshing.compareAndSet(false, true)) {
            return;
        }
        REFRESH_EXECUTOR.execute(() -> {
            try {
                var node = fetchCatalog(credentials);
                if (answered(node) && credentials.equals(credentials())) {
                    catalogCache = new CachedCatalog(credentials, System.currentTimeMillis(), node);
                }
            } finally {
                catalogRefreshing.set(false);
            }
        });
    }

    private void refreshSkillsInBackground(Credentials credentials) {
        if (!skillsRefreshing.compareAndSet(false, true)) {
            return;
        }
        REFRESH_EXECUTOR.execute(() -> {
            try {
                var fetched = fetchSkills(credentials, null, DEFAULT_SKILL_LIMIT);
                if (fetched.connected() && credentials.equals(credentials())) {
                    skillsCache = new CachedSkills(credentials, System.currentTimeMillis(), fetched.node(), fetched.digests());
                }
            } finally {
                skillsRefreshing.set(false);
            }
        });
    }

    /**
     * Updates hub-installed skills in the user skill root (the same managed copies a pull writes):
     * one named skill, or every hub-marked skill when {@code name} is absent. Only outdated skills
     * are replaced unless {@code force} — the same contract as {@code core-ai-cli skill update}.
     */
    public ObjectNode updateSkill(ObjectNode params) {
        var credentials = credentials();
        if (credentials == null) {
            throw RpcException.business("NOT_LOGGED_IN", "sign in to core-ai-server to update skills from the hub");
        }
        var client = new SkillHubClient(credentials.serverUrl(), credentials.apiKey(), credentials.insecure());
        var force = params.path("force").asBoolean(false);
        var name = Params.optionalText(params, "name");
        try {
            var updater = new SkillUpdater();
            var outcomes = name == null || name.isBlank()
                    ? updater.updateAll(client, SkillLocations.userSkillsDir(), force)
                    : updater.update(client, SkillLocations.userSkillsDir(), name.trim(), force);
            var node = Params.object();
            node.put("connected", true);
            var updated = node.putArray("updated");
            var skipped = node.putArray("skipped");
            for (var outcome : outcomes) {
                if (outcome.updated()) {
                    updated.add(outcome.qualifiedName());
                } else {
                    skipped.add(outcome.qualifiedName() + " " + outcome.reason());
                }
            }
            return node;
        } catch (RemoteApiException e) {
            throw RpcException.business("SKILL_UPDATE_FAILED",
                    "hub request failed (HTTP " + e.statusCode + "): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw RpcException.business("SKILL_UPDATE_FAILED", messageOf(e), e);
        }
    }

    public ObjectNode pullSkill(ObjectNode params) {
        var namespace = Params.requiredText(params, "namespace");
        var name = Params.requiredText(params, "name");
        var force = params.path("force").asBoolean(false);
        var credentials = credentials();
        if (credentials == null) {
            throw RpcException.business("NOT_LOGGED_IN", "sign in to core-ai-server to pull skills from the hub");
        }
        SkillHubClient.ArchiveResult archive;
        try {
            archive = new SkillHubClient(credentials.serverUrl(), credentials.apiKey(), credentials.insecure()).archive(namespace, name);
        } catch (RemoteApiException e) {
            throw RpcException.business("SKILL_PULL_FAILED",
                    "hub request failed (HTTP " + e.statusCode + "): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw RpcException.business("SKILL_PULL_FAILED", messageOf(e), e);
        }
        return install(SkillLocations.userSkillsDir(), credentials.serverUrl(), namespace, name, archive, force);
    }

    /** What the cached answers belong to: switching servers or accounts must not reuse them. */
    private record Credentials(String serverUrl, String apiKey, boolean insecure) {
    }

    private record CachedCatalog(Credentials credentials, long fetchedAt, ObjectNode node) {
    }

    private record CachedSkills(Credentials credentials, long fetchedAt, ObjectNode node, Map<String, String> digests) {
    }

    /** Skill freshness inputs for {@code skills/list}: null digests mean the server could not be read. */
    public record SkillDigests(Map<String, String> digests, boolean signedIn) {
    }

    private record SkillsFetch(ObjectNode node, Map<String, String> digests, boolean connected) {
    }
}
