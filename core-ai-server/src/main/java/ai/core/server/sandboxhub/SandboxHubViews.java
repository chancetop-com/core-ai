package ai.core.server.sandboxhub;

import ai.core.api.server.sandboxhub.SandboxHubCatalogResponse;
import ai.core.api.server.sandboxhub.SandboxHubGroup;
import ai.core.api.server.sandboxhub.SandboxHubSessionView;
import ai.core.api.server.sandboxhub.SandboxHubToolDetail;
import ai.core.api.server.sandboxhub.SandboxHubToolSearchRequest;
import ai.core.api.server.sandboxhub.SandboxHubToolSummary;
import ai.core.api.server.sandboxhub.SandboxHubToolsResponse;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Responses built from a {@link SandboxHubCatalogSnapshot}: pure functions over the owner's catalog, so
 * the pod that received a script's request can answer it even when the session lives elsewhere.
 *
 * @author xander
 */
public final class SandboxHubViews {
    private static final String SANDBOX_STATE_READY = "ready";
    private static final int DEFAULT_SEARCH_LIMIT = 20;
    private static final int MAX_SEARCH_LIMIT = 50;

    public static SandboxHubSessionView me(SandboxHubCatalogSnapshot snapshot, String sessionId, String sandboxId, String expiresAt) {
        var view = new SandboxHubSessionView();
        view.sessionId = sessionId;
        view.agentName = snapshot.agentName;
        view.sandboxId = sandboxId;
        view.sandboxState = SANDBOX_STATE_READY;
        view.expiresAt = expiresAt;
        view.toolCount = snapshot.detailsOrEmpty().size();
        view.contractVersion = SandboxHubService.CONTRACT_VERSION;
        return view;
    }

    public static SandboxHubCatalogResponse catalog(SandboxHubCatalogSnapshot snapshot, String sessionId, String sandboxId, String expiresAt) {
        var response = new SandboxHubCatalogResponse();
        response.sessionId = sessionId;
        response.agentName = snapshot.agentName;
        response.sandboxId = sandboxId;
        response.sandboxState = SANDBOX_STATE_READY;
        response.expiresAt = expiresAt;
        response.contractVersion = SandboxHubService.CONTRACT_VERSION;
        response.groups = groups(snapshot.detailsOrEmpty());
        response.tools = summaries(snapshot.detailsOrEmpty());
        response.datasets = snapshot.datasetsOrEmpty();
        return response;
    }

    public static SandboxHubToolsResponse tools(SandboxHubCatalogSnapshot snapshot, SandboxHubToolSearchRequest request) {
        var query = request != null ? request.query : null;
        var kind = request != null ? request.kind : null;
        var limit = request != null && request.limit != null ? request.limit : DEFAULT_SEARCH_LIMIT;
        limit = Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));

        var matched = new ArrayList<SandboxHubToolDetail>();
        for (var detail : snapshot.detailsOrEmpty()) {
            if (kind != null && !kind.isBlank() && !detail.kind.equalsIgnoreCase(kind.trim())) continue;
            if (query != null && !query.isBlank() && !matches(detail, query.trim())) continue;
            matched.add(detail);
        }

        var response = new SandboxHubToolsResponse();
        response.query = query;
        response.kind = kind;
        response.total = matched.size();
        response.tools = summaries(matched.size() > limit ? matched.subList(0, limit) : matched);
        return response;
    }

    public static SandboxHubToolDetail describe(SandboxHubCatalogSnapshot snapshot, String name) {
        for (var detail : snapshot.detailsOrEmpty()) {
            if (detail.name != null && detail.name.equals(name)) {
                if (!detail.callable) throw new BadRequestException("tool is not callable in a script: " + name);
                return detail;
            }
        }
        throw new NotFoundException("tool not found in this session: " + name + ", available: " + snapshot.detailsOrEmpty().size() + " tools");
    }

    public static List<SandboxHubToolSummary> summaries(List<SandboxHubToolDetail> details) {
        var summaries = new ArrayList<SandboxHubToolSummary>(details.size());
        for (var detail : details) {
            summaries.add(SandboxHubCatalog.summary(detail));
        }
        return summaries;
    }

    public static List<SandboxHubGroup> groups(List<SandboxHubToolDetail> details) {
        var counts = new LinkedHashMap<String, SandboxHubGroup>();
        for (var detail : details) {
            var group = counts.computeIfAbsent(detail.kind + "\u0000" + detail.group, ignored -> groupOf(detail));
            group.count = group.count + 1;
        }
        return List.copyOf(counts.values());
    }

    private static SandboxHubGroup groupOf(SandboxHubToolDetail detail) {
        var group = new SandboxHubGroup();
        group.kind = detail.kind;
        group.group = detail.group;
        group.path = detail.group;
        group.count = 0;
        return group;
    }

    private static boolean matches(SandboxHubToolDetail detail, String query) {
        var needle = query.toLowerCase(Locale.ROOT);
        return contains(detail.name, needle) || contains(detail.path, needle) || contains(detail.description, needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private SandboxHubViews() {
    }
}
