package ai.core.server.hub;

import ai.core.api.server.hub.HubCallView;
import ai.core.api.server.hub.ListHubCallsResponse;
import ai.core.server.domain.HubCall;
import ai.core.server.user.UserStore;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read side of {@code hub_calls}: the filtered, paged list of hub tool and model executions behind the
 * observability "Hub Calls" page. Every query is anchored to the {@code created_at} window so
 * MongoDB (with {@code notablescan} on) always has an index to scan; the user visibility scope is
 * applied by the caller, not here.
 *
 * @author stephen
 */
public class HubCallQueryService {
    public static final Set<String> KINDS = Set.of(HubCallAuditService.KIND_MCP_TOOL, HubCallAuditService.KIND_API_TOOL,
            HubCallAuditService.KIND_AGENT, HubCallAuditService.KIND_SANDBOX_TOOL, HubCallAuditService.KIND_DATASET,
            HubCallAuditService.KIND_MEDIA, HubCallAuditService.KIND_DECISION);
    private static final Set<String> STATES = Set.of("completed", "failed", "unknown");

    @Inject
    HubCallStore hubCallStore;
    @Inject
    UserStore userStore;

    public ListHubCallsResponse list(HubCallListFilter filter) {
        normalize(filter);
        var page = hubCallStore.list(filter);
        var response = new ListHubCallsResponse();
        response.total = page.total();
        response.calls = toViews(page.calls());
        return response;
    }

    private void normalize(HubCallListFilter filter) {
        if (hasText(filter.kind)) {
            var value = filter.kind.trim().toLowerCase(Locale.ROOT);
            if (!KINDS.contains(value)) throw new BadRequestException("kind must be one of " + KINDS);
            filter.kind = value;
        } else {
            filter.kind = null;
        }
        if (hasText(filter.userId)) filter.userId = filter.userId.trim();
        else filter.userId = null;
        if (hasText(filter.source)) filter.source = filter.source.trim().toLowerCase(Locale.ROOT);
        else filter.source = null;
        if (hasText(filter.state)) {
            var value = filter.state.trim().toLowerCase(Locale.ROOT);
            if (!STATES.contains(value)) throw new BadRequestException("state must be one of " + STATES);
            filter.state = value;
        } else {
            filter.state = null;
        }
    }

    private List<HubCallView> toViews(List<HubCall> calls) {
        var accountCache = new HashMap<String, Account>();
        return calls.stream().map(call -> toView(call, accountCache)).toList();
    }

    private HubCallView toView(HubCall call, Map<String, Account> accountCache) {
        var view = new HubCallView();
        view.id = call.id;
        view.kind = call.kind;
        view.source = call.source;
        view.target = call.target;
        view.group = call.group;
        view.name = call.name;
        view.refId = call.refId;
        view.userId = call.userId;
        view.userType = call.userType;
        var account = accountFor(call.userId, accountCache);
        view.userName = account.name;
        view.userEmail = account.email;
        view.success = call.success;
        view.isError = call.isError;
        view.statusCode = call.statusCode;
        view.durationMs = call.durationMs;
        view.outputBytes = call.outputBytes;
        view.errorMessage = call.errorMessage;
        view.argsHash = call.argsHash;
        view.argsPreview = call.argsPreview;
        view.taskId = call.taskId;
        view.contextId = call.contextId;
        view.inputTokens = call.inputTokens;
        view.outputTokens = call.outputTokens;
        view.createdAt = call.createdAt;
        return view;
    }

    private Account accountFor(String userId, Map<String, Account> accountCache) {
        if (!hasText(userId)) return Account.EMPTY;
        return accountCache.computeIfAbsent(userId, id -> userStore.get(id)
                .map(user -> new Account(user.name, user.email))
                .orElse(Account.EMPTY));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Account(String name, String email) {
        private static final Account EMPTY = new Account(null, null);
    }
}
