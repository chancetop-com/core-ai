package ai.core.cli.appserver;

import ai.core.api.server.hubcatalog.HubCatalogResponse;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.hub.catalog.CatalogHubClient;
import ai.core.cli.hub.skill.SkillHubClient;
import ai.core.cli.hub.skill.SkillHubMarker;
import ai.core.cli.hub.skill.SkillInstallConflictException;
import ai.core.cli.hub.skill.SkillInstaller;
import ai.core.cli.hub.skill.SkillLocations;
import ai.core.cli.http.RemoteApiException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

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

    /**
     * Installs a fetched archive; package-visible so tests can drive the install paths against a
     * temp directory without a server. A conflict the user could resolve by confirming comes back
     * as {@code SKILL_CONFLICT}, anything else as {@code SKILL_PULL_FAILED}.
     */
    static ObjectNode install(Path baseDir, String serverUrl, String namespace, String name,
                              SkillHubClient.ArchiveResult archive, boolean force) {
        var qualified = namespace + "/" + name;
        var targetDir = baseDir.resolve(namespace).resolve(name);
        var digest = archive.digest() == null ? "" : archive.digest();
        var source = new SkillHubMarker.Marker(qualified, archive.id(), digest, serverUrl, null);
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
        return new Credentials(auth.serverUrl(), auth.apiKey());
    }

    public ObjectNode catalog() {
        var node = Params.object();
        var credentials = credentials();
        if (credentials == null) {
            return notConnected(node, "not_logged_in");
        }
        try {
            var response = new CatalogHubClient(credentials.serverUrl(), credentials.apiKey(), false).catalog();
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
            LOGGER.warn("hub catalog fetch failed: {}", e.getMessage());
            return notConnected(node, "unreachable").put("message", messageOf(e));
        }
    }

    public ObjectNode skills(ObjectNode params) {
        var node = Params.object();
        var credentials = credentials();
        if (credentials == null) {
            return notConnected(node, "not_logged_in");
        }
        try {
            var query = Params.optionalText(params, "query");
            int limit = Math.max(1, Math.min(Params.intParam(params, "limit", DEFAULT_SKILL_LIMIT), MAX_SKILL_LIMIT));
            var response = new SkillHubClient(credentials.serverUrl(), credentials.apiKey())
                    .search(query, null, null, limit);
            node.put("connected", true);
            node.put("serverUrl", credentials.serverUrl());
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
            }
            return node;
        } catch (RemoteApiException e) {
            return unreachable(node, e);
        } catch (RuntimeException e) {
            LOGGER.warn("hub skill list failed: {}", e.getMessage());
            return notConnected(node, "unreachable").put("message", messageOf(e));
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
            archive = new SkillHubClient(credentials.serverUrl(), credentials.apiKey()).archive(namespace, name);
        } catch (RemoteApiException e) {
            throw RpcException.business("SKILL_PULL_FAILED",
                    "hub request failed (HTTP " + e.statusCode + "): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw RpcException.business("SKILL_PULL_FAILED", messageOf(e), e);
        }
        return install(SkillLocations.userSkillsDir(), credentials.serverUrl(), namespace, name, archive, force);
    }

    private record Credentials(String serverUrl, String apiKey) {
    }
}
