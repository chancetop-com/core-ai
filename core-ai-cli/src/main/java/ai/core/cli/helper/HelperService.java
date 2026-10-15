package ai.core.cli.helper;

import ai.core.api.server.agenthub.AgentHubDetail;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.hub.agent.AgentHubClient;
import ai.core.cli.hub.agent.LocalAttachmentUploader;
import ai.core.cli.http.RemoteApiException;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Management surface of helpers: the validation rules around {@link HelperConfigStore} plus the
 * hub summary lookup that snapshots the agent description when a helper is added. Error codes
 * follow {@code design-agent-helpers.md} (§4.4).
 *
 * @author stephen
 */
public class HelperService {
    private static final String CODE_NOT_LOGGED_IN = "NOT_LOGGED_IN";
    private static final String CODE_HELPER_EXISTS = "HELPER_EXISTS";
    private static final String CODE_HELPER_LIMIT = "HELPER_LIMIT";
    private static final String CODE_HELPER_NOT_FOUND = "HELPER_NOT_FOUND";
    private static final String CODE_AGENT_NOT_FOUND = "AGENT_NOT_FOUND";
    private static final String CODE_HELPER_FAILED = "HELPER_FAILED";

    /** Resolved per call so a login after engine start is picked up without a restart. */
    public static AgentHubClient clientOrNull() {
        var auth = loginOrNull();
        return auth == null ? null : new AgentHubClient(auth.serverUrl(), auth.apiKey(), auth.insecure());
    }

    /** Same login as {@link #clientOrNull()}; used to deliver a delegation's message attachments. */
    public static LocalAttachmentUploader uploaderOrNull() {
        var auth = loginOrNull();
        return auth == null ? null : new LocalAttachmentUploader(auth.serverUrl(), auth.apiKey(), auth.insecure());
    }

    private static AuthConfig loginOrNull() {
        var auth = AuthConfig.load();
        if (auth == null || auth.apiKey() == null || auth.serverUrl() == null) {
            return null;
        }
        return auth;
    }

    private final HelperConfigStore store;
    private final Supplier<AgentHubClient> clientSupplier;

    public HelperService(HelperConfigStore store, Supplier<AgentHubClient> clientSupplier) {
        this.store = store;
        this.clientSupplier = clientSupplier;
    }

    public boolean connected() {
        return clientSupplier.get() != null;
    }

    public List<HelperEntry> list() {
        return store.list();
    }

    public HelperEntry add(String agentId, String whenToUse) {
        if (store.find(agentId) != null) {
            throw new HelperException(CODE_HELPER_EXISTS, "helper already configured: " + agentId);
        }
        if (store.list().size() >= HelperConfigStore.MAX_HELPERS) {
            throw new HelperException(CODE_HELPER_LIMIT, "at most " + HelperConfigStore.MAX_HELPERS + " helpers can be loaded");
        }
        var client = clientSupplier.get();
        if (client == null) {
            throw new HelperException(CODE_NOT_LOGGED_IN, "sign in to core-ai-server before adding a helper");
        }
        var detail = show(client, agentId);
        var entry = new HelperEntry();
        entry.agentId = agentId;
        entry.name = detail.name != null && !detail.name.isBlank() ? detail.name : agentId;
        entry.description = detail.description;
        entry.whenToUse = whenToUse == null || whenToUse.isBlank() ? null : whenToUse;
        entry.enabled = Boolean.TRUE;
        entry.addedAt = Instant.now().toString();
        store.add(entry);
        return store.find(agentId);
    }

    public HelperEntry update(String agentId, String whenToUse, Boolean enabled) {
        if (!store.update(agentId, whenToUse, enabled)) {
            throw new HelperException(CODE_HELPER_NOT_FOUND, "helper not found: " + agentId);
        }
        return store.find(agentId);
    }

    public void remove(String agentId) {
        if (!store.remove(agentId)) {
            throw new HelperException(CODE_HELPER_NOT_FOUND, "helper not found: " + agentId);
        }
    }

    private AgentHubDetail show(AgentHubClient client, String agentId) {
        try {
            return client.show(agentId);
        } catch (RemoteApiException e) {
            throw mapShowError(agentId, e);
        }
    }

    private HelperException mapShowError(String agentId, RemoteApiException e) {
        if (e.statusCode == 404) {
            return new HelperException(CODE_AGENT_NOT_FOUND, "agent is not visible on the hub: " + agentId, e);
        }
        if (e.statusCode == 401) {
            return new HelperException(CODE_NOT_LOGGED_IN, "sign in to core-ai-server before adding a helper", e);
        }
        return new HelperException(CODE_HELPER_FAILED, "failed to resolve agent (HTTP " + e.statusCode + "): " + e.getMessage(), e);
    }
}
